package com.yuzi.mtrext.client;

import com.yuzi.mtrext.YuziMtrExt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * 量一段资源包音效有多长（毫秒）。<b>纯客户端</b>。
 *
 * <h2>为什么要自己造</h2>
 *
 * <p>「到站前 X 秒<b>播完</b>」必须知道音频时长才能倒推起点，而 MTR 整个仓库
 * 没有任何读音频长度的能力 —— 上游自己撞上这个问题时给的答案是让用户手填
 * （{@code sound/BveConfigFile.java:49} 的 {@code doorCloseSoundLength}）。
 * Minecraft 的 {@code SoundManager} 也只管播放，不暴露时长。
 *
 * <h2>怎么量</h2>
 *
 * <p>直接读 Ogg 容器，不解码音频：
 * <ol>
 * <li>首包里找 {@code 0x01 "vorbis"}（Vorbis 标识头），偏移 +12 处 4 字节小端是<b>采样率</b>；</li>
 * <li>从文件尾倒着找最后一个 {@code "OggS"} 页头，偏移 +6 处 8 字节小端是
 *     <b>granule position</b> —— 对 Vorbis 而言就是到该页为止的总采样数；</li>
 * <li>时长 = granule / 采样率。</li>
 * </ol>
 * 拿 MTR-TTS 的 481 个成品 ogg 全量验过，采样率 44100、granule 均有效。
 *
 * <h2>拿不到时长怎么办</h2>
 *
 * <p>一律返回 0，调用方当作「未知」。界面上那一栏可以手改，所以自动量失败不会卡住配置 ——
 * 这也是为什么时长要<b>存进方块实体</b>而不是每次现算：服务端没有资源包，
 * 触发的时候它只能用存下来的那个数。
 */
public final class OggDuration {

	private OggDuration() {
	}

	/** 只读文件尾这么多字节去找最后一页。Ogg 页最大 ~65 KB，64 KB 足够覆盖最后一页的页头。 */
	private static final int TAIL_BYTES = 1 << 16;

	/** 找 Vorbis 标识头时只看文件开头这么多字节；它一定在第一个包里。 */
	private static final int HEAD_BYTES = 4096;

	/**
	 * @param soundId sounds.json 里的音效 id，如 {@code mtr_custom:s2/next_xujiahui_cmn}
	 * @return 毫秒；量不出来返回 0
	 */
	public static int millis(String soundId) {
		try {
			final MinecraftClient minecraftClient = MinecraftClient.getInstance();
			final WeightedSoundSet weightedSoundSet = minecraftClient.getSoundManager().get(new Identifier(soundId));
			if (weightedSoundSet == null) {
				return 0;
			}

			// 一个 sounds.json 条目可以挂多个候选文件（weight 随机选一个），这里就取随机那一个：
			// 报站音频都是 1:1 的，多候选的场景本来也谈不上「确定的时长」。
			final Sound sound = weightedSoundSet.getSound(Random.create());
			// type=event 的条目指向另一个音效而不是文件，getLocation() 对它没有意义。
			if (sound == null || sound.getRegistrationType() != Sound.RegistrationType.FILE) {
				return 0;
			}

			final Optional<Resource> resource = minecraftClient.getResourceManager().getResource(sound.getLocation());
			if (!resource.isPresent()) {
				return 0;
			}

			try (InputStream inputStream = resource.get().getInputStream()) {
				final byte[][] headAndTail = readHeadAndTail(inputStream);
				return parse(headAndTail[0], headAndTail[1]);
			}
		} catch (Exception e) {
			YuziMtrExt.LOGGER.warn("量不出 {} 的时长，按 0 处理", soundId, e);
			return 0;
		}
	}

	static int parse(byte[] head, byte[] tail) {
		final int sampleRate = findSampleRate(head);
		if (sampleRate <= 0) {
			return 0;
		}
		final long granule = findLastGranulePosition(tail);
		// granule 为 -1（0xFFFF…）是「该页没有结束任何包」，只有截断的文件才会出现在末页；
		// 当作量不出来处理。
		if (granule <= 0) {
			return 0;
		}
		return (int) (granule * 1000L / sampleRate);
	}

	/** Vorbis 标识头：{@code 0x01 'v''o''r''b''i''s'} + version(4) + channels(1) + rate(4, 小端)。 */
	private static int findSampleRate(byte[] bytes) {
		for (int i = 0; i + 15 < bytes.length; i++) {
			if (bytes[i] == 1 && bytes[i + 1] == 'v' && bytes[i + 2] == 'o' && bytes[i + 3] == 'r'
					&& bytes[i + 4] == 'b' && bytes[i + 5] == 'i' && bytes[i + 6] == 's') {
				return (int) readLittleEndian(bytes, i + 12, 4);
			}
		}
		return 0;
	}

	/**
	 * 页头：{@code "OggS"} + version(1) + headerType(1) + granulePosition(8, 小端)。
	 *
	 * <p>Ogg <b>不转义</b> capture pattern，音频数据里出现 {@code "OggS"} 这四个字节是完全可能的，
	 * 所以光认这四个字节会读到垃圾。再校验两处几乎白给的字段：version 必须是 0，
	 * headerType 必须带 EOS 位（0x04）—— 我们要的本来就是最后一页。
	 */
	private static long findLastGranulePosition(byte[] bytes) {
		for (int i = bytes.length - 14; i >= 0; i--) {
			if (bytes[i] == 'O' && bytes[i + 1] == 'g' && bytes[i + 2] == 'g' && bytes[i + 3] == 'S'
					&& bytes[i + 4] == 0 && (bytes[i + 5] & 0x04) != 0) {
				return readLittleEndian(bytes, i + 6, 8);
			}
		}
		return 0;
	}

	private static long readLittleEndian(byte[] bytes, int offset, int length) {
		long value = 0;
		for (int i = length - 1; i >= 0; i--) {
			value = (value << 8) | (bytes[offset + i] & 0xFFL);
		}
		return value;
	}

	/**
	 * 流式读出<b>开头 {@link #HEAD_BYTES} 字节</b>和<b>末尾 {@link #TAIL_BYTES} 字节</b>，
	 * 中间的一概不留。
	 *
	 * <p>我们只需要这两头，而音效列表里什么都可能有 —— 选到一个几十 MB 的 BGM 时，
	 * 整个读进内存会在渲染线程上卡一下。滑动尾窗把峰值内存钉死在两个固定缓冲区。
	 *
	 * @return {@code [开头, 结尾]}，都已按实际长度截好
	 */
	private static byte[][] readHeadAndTail(InputStream inputStream) throws IOException {
		final byte[] head = new byte[HEAD_BYTES];
		final byte[] tail = new byte[TAIL_BYTES];
		final byte[] buffer = new byte[TAIL_BYTES];
		int headLength = 0;
		int tailLength = 0;
		int read;

		while ((read = inputStream.read(buffer)) > 0) {
			if (headLength < HEAD_BYTES) {
				final int copy = Math.min(read, HEAD_BYTES - headLength);
				System.arraycopy(buffer, 0, head, headLength, copy);
				headLength += copy;
			}
			if (read >= TAIL_BYTES) {
				System.arraycopy(buffer, read - TAIL_BYTES, tail, 0, TAIL_BYTES);
				tailLength = TAIL_BYTES;
			} else {
				// 往左挤出 read 个字节的位置，再把新读到的接在后面。
				final int keep = Math.min(tailLength, TAIL_BYTES - read);
				System.arraycopy(tail, tailLength - keep, tail, 0, keep);
				System.arraycopy(buffer, 0, tail, keep, read);
				tailLength = keep + read;
			}
		}

		return new byte[][]{Arrays.copyOf(head, headLength), Arrays.copyOf(tail, tailLength)};
	}
}
