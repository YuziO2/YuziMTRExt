package com.yuzi.mtrext.client;

import net.minecraft.client.sound.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 从音频流的中途开始：先把开头 {@code skipMillis} 毫秒解码出来丢掉，再照常往下给。
 * 跳过量按帧对齐，所以返回的第一块从完整的一帧开始。跳过量超过全长时，第一次读就是空的 —— 引擎当作放完，
 * 这正是「已经放完就不放了」。
 */
final class SkippingAudioStream implements AudioStream {

	private final AudioStream inner;
	private long skipBytes;

	SkippingAudioStream(AudioStream inner, long skipMillis) {
		this.inner = inner;
		final AudioFormat format = inner.getFormat();
		final int frameSize = Math.max(1, format.getChannels() * Math.max(1, format.getSampleSizeInBits() / 8));
		skipBytes = (long) (format.getSampleRate() * skipMillis / 1000) * frameSize;
	}

	@Override
	public AudioFormat getFormat() {
		return inner.getFormat();
	}

	@Override
	public ByteBuffer getBuffer(int size) throws IOException {
		while (skipBytes > 0) {
			final ByteBuffer buffer = inner.getBuffer(size);
			if (buffer == null || !buffer.hasRemaining()) {
				return buffer;
			}
			if (buffer.remaining() <= skipBytes) {
				skipBytes -= buffer.remaining();
			} else {
				buffer.position(buffer.position() + (int) skipBytes);
				skipBytes = 0;
				return buffer;
			}
		}
		return inner.getBuffer(size);
	}

	@Override
	public void close() throws IOException {
		inner.close();
	}
}
