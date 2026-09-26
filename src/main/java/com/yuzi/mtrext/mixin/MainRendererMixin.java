package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.client.RopeBarrierDrawing;
import com.yuzi.mtrext.psd.RopeBarrierPositions;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.render.MainRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * 两件事：把绳网的绘制搬进 MTR 的主渲染钩子（这样光影下才有影子），以及修上游那个
 * 「开光影后方块实体渲染的东西整个消失」的 bug。
 *
 * <h2>为什么绳网要在这里画</h2>
 *
 * <p>{@code MainRenderer extends EntityRenderer<EntityRendering>}，挂在一个虚拟实体上
 * （{@code InitClient.java:192}）—— <b>实体会进阴影 pass</b>，MTR 自己也是这么让轨道和
 * 车辆的 OBJ 投出正确影子的。方块实体渲染器走不到这条路径。
 *
 * <p><b>影子要生效，还得关掉 MTR 自己的 {@code disableShadowsForShaders}</b>
 * （{@code config/mtr.json}）。那个开关打开时 MTR 在阴影 pass 直接 return。
 *
 * <h2>上游 bug 与正确的修法</h2>
 *
 * <p>{@code MainRenderer.render} 用双缓冲：{@code CURRENT ← RENDERS}，然后清空
 * {@code RENDERS}，再绘制 {@code CURRENT}。方块实体渲染器（屏蔽门、电梯门）通过
 * {@code scheduleRender} 往 {@code RENDERS} 里塞，而原版<b>实体先于方块实体渲染</b>。
 * 开光影时这个方法每帧被调两次：阴影 pass 那次先把队列搬走并清空，轮到主 pass 已经空了
 * —— 主画面什么都不画。
 *
 * <p><b>第一版修法是错的</b>：当时用 {@code @Redirect} 让阴影 pass 不清空 {@code RENDERS}。
 * 但车辆和轨道是在 {@code MainRenderer.render} <b>内部</b>调度的
 * （{@code MainRenderer.java:112,114}，在第 118 行的交换之前），而阴影 pass 里
 * {@code millisElapsed = 0} 不推进位置、主 pass 才 {@code simulate}。于是主 pass 的队列里
 * 同时存在「阴影 pass 那份旧位置」和「自己这份新位置」，两份差一帧行程一起画出来 ——
 * <b>列车一跑就是重影</b>（停站时两份重合，看不出来）。
 *
 * <p>现在改成：阴影 pass 开始前<b>快照</b> {@code RENDERS}，这次调用结束后<b>还原</b>。
 * 于是阴影 pass 照常消费队列（方块实体的内容被画进阴影图），还原后主 pass 又能拿到同一份；
 * 而两个 pass 各自在 {@code render} 内部调度的东西（车辆、轨道、绳网）都只属于自己那一次，
 * 不会串到对方。
 */
@Mixin(value = MainRenderer.class, remap = false)
public abstract class MainRendererMixin {

	@Shadow
	@Final
	private static ObjectArrayList<ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>>> RENDERS;

	/**
	 * 本次 render 调用是不是阴影 pass —— 在 HEAD 算一次给 RETURN 复用。
	 *
	 * <p>{@code OptimizedRenderer.renderingShadows()} 内部是
	 * {@code Thread.currentThread().getStackTrace()} 再逐帧比 {@code net.irisshaders} /
	 * {@code net.optifine} 前缀（字节码已核实），一次调用里算一遍就够。
	 */
	private static boolean yuzimtr$shadowPassThisCall;

	/** 阴影 pass 的队列快照。不带初始化器 —— 见 README「注入点约束」。 */
	private static ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>> yuzimtr$backup;

	@Inject(
			method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V",
			at = @At("HEAD")
	)
	private static void yuzimtr$renderRopeBarriers(GraphicsHolder graphicsHolder, Vector3d offset, CallbackInfo callbackInfo) {
		final boolean shadowPass = OptimizedRenderer.renderingShadows();
		yuzimtr$shadowPassThisCall = shadowPass;

		// 快照必须赶在本次调用里任何东西被调度之前 —— 包括下面我们自己画的绳网
		if (shadowPass && Config.get().fixShadowPassEatingRenderQueue) {
			yuzimtr$snapshot();
		}

		if (!Config.get().ropeBarrier) {
			return;
		}
		// 和 MainRenderer 自己的判断保持一致（MainRenderer.java:86-89）：那个开关打开时
		// 它在阴影 pass 直接 return，末尾的 flush 也就不会执行，我们调度进去的会残留。
		if (shadowPass && org.mtr.mod.config.Config.getClient().getDisableShadowsForShaders()) {
			return;
		}

		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) {
			return;
		}
		RopeBarrierPositions.forEach(blockPos -> RopeBarrierDrawing.draw(clientWorld, blockPos));
	}

	@Inject(
			method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V",
			at = @At("RETURN")
	)
	private static void yuzimtr$restoreRenderQueue(GraphicsHolder graphicsHolder, Vector3d offset, CallbackInfo callbackInfo) {
		if (yuzimtr$shadowPassThisCall && Config.get().fixShadowPassEatingRenderQueue) {
			yuzimtr$restore();
		}
	}

	/** 深一层的拷贝：值列表也要复制，否则主 pass 往同一个列表里追加会污染快照。 */
	private static void yuzimtr$snapshot() {
		if (yuzimtr$backup == null) {
			yuzimtr$backup = new ObjectArrayList<>();
		}
		yuzimtr$backup.clear();
		for (final ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>> stage : RENDERS) {
			for (final Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>> layer : stage) {
				final Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>> copy = new Object2ObjectArrayMap<>();
				layer.forEach((key, value) -> copy.put(key, new ObjectArrayList<>(value)));
				yuzimtr$backup.add(copy);
			}
		}
	}

	private static void yuzimtr$restore() {
		if (yuzimtr$backup == null) {
			return;
		}
		int index = 0;
		for (final ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>> stage : RENDERS) {
			for (final Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>> layer : stage) {
				layer.clear();
				if (index < yuzimtr$backup.size()) {
					layer.putAll(yuzimtr$backup.get(index));
				}
				index++;
			}
		}
	}
}
