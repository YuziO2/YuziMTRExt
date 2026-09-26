package com.yuzi.mtrext.psd;

import com.yuzi.mtrext.Config;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.BlockEntityType;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.SoundCategory;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mod.block.IBlock;

/**
 * 绳索和立柱共用的门开度状态。
 *
 * <p>逻辑逐行照抄 MTR4 的 {@code BlockPSDAPGDoorBase.BlockEntityBase}：渲染器每帧驱动
 * {@link #tick}，{@code doorTarget} 倒数到负就说明这一帧没有车推值 → 按
 * {@code DOOR_MOVE_TIME/2} 落回 0。门值只存<b>下半块</b>，上半块转过去取
 * （双高方块的理由见 {@link BlockRopeBarrier} 类注释里那个跳跃穿越的 bug）。
 *
 * <p>纯客户端渲染态，不落盘。
 */
public class RopeBarrierBlockEntity extends BlockEntityExtension {

	private double doorValue;
	private int doorTarget;
	private double lastTrainDoorValue = -1;
	private long lastPushMillis;
	private boolean closing;
	private int pendingSound;
	private long lastSoundMillis;

	/** 下降动画的本地起点（0 = 还没开始）。倒计时只当触发器，动画自己用本地时钟插值。 */
	private long descentStartMillis;
	private int warningsLeft;
	private long nextWarningMillis;

	public RopeBarrierBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void readCompoundTag(CompoundTag compoundTag) {
	}

	@Override
	public void writeCompoundTag(CompoundTag compoundTag) {
	}

	private boolean isUpper() {
		final BlockState state = getCachedState2();
		return state != null && IBlock.getStatePropertySafe(state, IBlock.HALF) == IBlock.DoubleBlockHalf.UPPER;
	}

	/** 取本柱/本段的下半块实体；门值只存在那上面。 */
	public RopeBarrierBlockEntity bottom() {
		final World world = getWorld2();
		if (world == null || !isUpper()) {
			return this;
		}
		final org.mtr.mapping.holder.BlockEntity blockEntity = world.getBlockEntity(getPos2().down());
		return blockEntity != null && blockEntity.data instanceof RopeBarrierBlockEntity
				? (RopeBarrierBlockEntity) blockEntity.data : this;
	}

	/**
	 * 由 {@link com.yuzi.mtrext.mixin.RenderVehicleHelperMixin} 每帧推入。
	 *
	 * <p>传进来的是 {@code RenderVehicles.java:168} 的
	 * {@code max(doorBlockedAmount, persistentVehicleData.getDoorValue() * 2)}，
	 * 所以除以 2 才是车门本身的开度（有人挡门时 doorBlockedAmount 可能更大，
	 * 那种情况下这个换算会偏小，但那也是唯一能拿到的量）。
	 *
	 * <p>开合两个方向用不同的映射：
	 * <ul>
	 * <li><b>开门</b>：绳子按 {@code ropeBarrierSpeedMultiplier} 倍于车门的速度升起，
	 *     先于车门到位。</li>
	 * <li><b>关门</b>：要求「车门关闭前 {@code ropeBarrierCloseLeadMillis} 毫秒绳子已完全落下」。
	 *     这个不需要预判 —— 门从开始关到关完固定是 {@link Vehicle#DOOR_MOVE_TIME}，
	 *     所以车门开度 {@code d} 距离关完还有 {@code d × DOOR_MOVE_TIME} 毫秒。
	 *     令绳子在 {@code d = lead / DOOR_MOVE_TIME} 处归零，那一刻正好距离关完 lead 毫秒。</li>
	 * </ul>
	 */
	public void setDoorValue(double vehicleDoorValue) {
		final RopeBarrierBlockEntity target = bottom();
		target.lastPushMillis = System.currentTimeMillis();
		final Config config = Config.get();
		final double trainDoor = Utilities.clamp(vehicleDoorValue / 2, 0, 1);

		// 方向必须是**粘滞**的：pushAlongRow 每帧会被调用不止一次（每个命中的 doorway 一次），
		// 后续几次拿到的是同一个值。如果按「本次是否比上次小」逐次判断，第二次就会因为
		// 「相等 ≠ 下降」掉进开门分支，把提前落下的值覆盖回去 —— 表现就是提前量完全不生效。
		if (target.lastTrainDoorValue >= 0) {
			if (trainDoor < target.lastTrainDoorValue - 1e-6) {
				// 关门提示音由 updateFromCountdown 按倒计时提前发；拿不到倒计时时无声
				target.closing = true;
			} else if (trainDoor > target.lastTrainDoorValue + 1e-6) {
				if (target.closing || target.lastTrainDoorValue < 1e-6) {
					target.pendingSound = 1;
				}
				target.closing = false;
				target.resetCloseSequence();
			}
		}
		target.lastTrainDoorValue = trainDoor;

		// 开门时正常跟随；关门交给 updateFromCountdown 驱动的下降动画（如果拿得到倒计时）
		if (!target.closing) {
			target.doorValue = Utilities.clamp(trainDoor * config.ropeBarrierSpeedMultiplier, 0, 1);
		} else if (target.descentStartMillis == 0) {
			// 退化路径：没有倒计时（旁边没站台/没班次），只能反应式地跟着车门落
			final double threshold = Utilities.clamp(
					config.ropeBarrierCloseLeadMillis / (double) Vehicle.DOOR_MOVE_TIME, 0, 0.95);
			target.doorValue = Utilities.clamp(
					trainDoor <= threshold ? 0 : (trainDoor - threshold) / (1 - threshold), 0, 1);
		}
		target.doorTarget = 1;
	}

	public double getDoorValue() {
		return bottom().doorValue;
	}

	/**
	 * 用发车倒计时提前启动关门序列。每帧由渲染器调用。
	 *
	 * <p>时间轴以 t =「距发车还有多少毫秒」为准：车门 t=4200 开始关、t=1000 关完。
	 * 于是
	 * <ul>
	 * <li>绳子落到底：t = 1000 + closeLead</li>
	 * <li>绳子开始下降：再往前 closeDuration</li>
	 * <li>第一声提示音：再往前 warningLead</li>
	 * </ul>
	 *
	 * <p>倒计时只当<b>触发器</b>，一旦触发就锁存本地起点、用本地时钟插值 ——
	 * 到站表是缓存+网络来的，粒度粗、会跳变，逐帧拿它驱动动画会一顿一顿的。
	 */
	public void updateFromCountdown(long millisUntilDeparture) {
		if (isUpper()) {
			return;
		}
		final Config config = Config.get();
		final long now = System.currentTimeMillis();

		// 只有「本次停站确实收到过推值、而且车门已经完全打开」才允许启动关门序列。
		// 少了这个守卫会出三种错：
		//   1. 从没被推过值的绳排（车被遮挡剔除、绳排离 doorway 超过 1 格、门开在另一侧）
		//      会凭空升到顶再落一遍，还响三声；
		//   2. 开门途中就开始响关门提示音 —— 那时 setDoorValue 每帧判定为「上升」并调用
		//      resetCloseSequence 清掉锁存，这里又立刻重新锁存，于是每秒响一声直到门开完；
		//   3. 停站时间短时，下降也会被反复清零重建而抖动。
		// 还要求「最近确实在被推值」：lastTrainDoorValue 只在 setDoorValue 里赋值，推值一旦
		// 中断（列车被遮挡剔除、驶离、doorway 移出范围）它就冻结在中断那一刻。若冻结在高位，
		// 下一趟车的倒计时越过阈值时，这个根本没被推值的绳排会凭空响三声铃。
		final boolean armed = lastTrainDoorValue >= 0.99
				&& now - lastPushMillis < PUSH_STALE_MILLIS;

		if (armed && millisUntilDeparture != Long.MIN_VALUE) {
			final long downAt = RopeBarriers.DOOR_DELAY + config.ropeBarrierCloseLeadMillis;
			final long descendAt = downAt + config.ropeBarrierCloseDurationMillis;
			final long warnAt = descendAt + config.ropeBarrierWarningLeadMillis;

			if (warningsLeft == 0 && descentStartMillis == 0 && millisUntilDeparture <= warnAt) {
				warningsLeft = Math.max(0, config.ropeBarrierWarningCount);
				nextWarningMillis = now;
			}
			if (descentStartMillis == 0 && millisUntilDeparture <= descendAt) {
				// 从当前高度**续接**，而不是从 1 重新插值。到站表有最多三秒多的缓存滞后，
				// 倒计时迟到时绳子已经在反应式下落了；直接置 1 会「落到一半又弹回顶部再落」。
				final double duration = Math.max(1, config.ropeBarrierCloseDurationMillis);
				descentStartMillis = now - (long) ((1 - doorValue) * duration);
				closing = true;
			}
		}

		if (warningsLeft > 0 && now >= nextWarningMillis) {
			warningsLeft--;
			nextWarningMillis = now + Math.max(200, config.ropeBarrierWarningIntervalMillis);
			pendingSound = -1;
		}

		if (descentStartMillis > 0) {
			final double duration = Math.max(1, config.ropeBarrierCloseDurationMillis);
			doorValue = Utilities.clamp(1 - (now - descentStartMillis) / duration, 0, 1);
		}
	}

	/** 车门重新开启时把关门序列的锁存清掉，好让下一趟车重新走一遍。 */
	private void resetCloseSequence() {
		descentStartMillis = 0;
		warningsLeft = 0;
	}

	/** 超过这么久没收到推值，就认为旁边已经没有在停靠的车了。 */
	private static final long PUSH_STALE_MILLIS = 1000;

	/** 只有下半块需要 tick。 */
	public void tick(float tickDelta) {
		if (isUpper()) {
			return;
		}
		if (doorTarget >= 0) {
			doorTarget--;
		} else {
			doorValue = Math.max(0, doorValue - tickDelta * 20 / Vehicle.DOOR_MOVE_TIME * 2);
		}
	}

	/**
	 * 在开合<b>动作开始的那一刻</b>放一声，作为提示音 —— 对应 psda {@code node.js:144-151}
	 * 在门开度翻转时触发 {@code psda.open}/{@code psda.close}，并按时间节流。
	 *
	 * <p>开门侧在方向翻转的那一刻响一声。关门侧不走这里的方向翻转 ——
	 * 由 {@link #updateFromCountdown} 按发车倒计时<b>提前</b>发若干声（见
	 * {@code ropeBarrierWarningCount}）；拿不到倒计时时关门就没有提示音。
	 *
	 * <p>必须用<b>按坐标</b>的 playSound 重载。{@code playSound(PlayerEntity except, …)}
	 * 在客户端只有当 {@code except} 就是客户端玩家时才会出声，传 {@code null} 等于不播 ——
	 * 这就是之前完全听不到的原因。
	 */
	public void playSoundOnChange() {
		if (!Config.get().ropeBarrierSound || isUpper() || pendingSound == 0) {
			return;
		}
		final int sound = pendingSound;
		pendingSound = 0;
		final long now = System.currentTimeMillis();
		if (now - lastSoundMillis < 1000) {
			return;
		}
		lastSoundMillis = now;
		final World world = getWorld2();
		if (world != null) {
			final BlockPos pos = getPos2();
			world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					(sound > 0 ? RopeBarriers.SOUND_OPEN : RopeBarriers.SOUND_CLOSE).get(),
					SoundCategory.BLOCKS, 1, 1, false);
		}
	}
}
