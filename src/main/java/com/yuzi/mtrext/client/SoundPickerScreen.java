package com.yuzi.mtrext.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.mapper.AbstractSoundInstanceExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.SoundHelper;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.MTRScreenBase;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * 从资源包里挑一段音效，带试听。
 *
 * <p>候选来自 {@code AbstractSoundInstanceExtension.iterateSoundIds}，它编译到
 * {@code SoundManager.getAvailableSounds()} —— 也就是客户端<b>已加载资源包里 sounds.json 的全部键</b>，
 * 和 MTR 广播器界面用的是同一份。
 *
 * <p>构造 {@code DashboardList} 时 {@code playSound} 传 false：那是按钮自己的点击音效，
 * 开着会和试听撞在一起。MTR 的 {@code TrainAnnouncerScreen} 也是传 false。
 *
 * <p>列表项的 id 用的是<b>下标</b>（音效 id 是字符串，而 {@code DashboardListItem.id} 是 long）。
 * {@code setData(ObjectArrayList, …)} 这个重载不会重排（只有 {@code ObjectSet} 那个会），
 * 所以自己排好序之后下标始终对得上。
 */
public class SoundPickerScreen extends MTRScreenBase implements IGui {

	private final ObjectArrayList<String> soundIds = new ObjectArrayList<>();
	private final DashboardList soundsList;

	/** 搜索词留在界面外面，来回进出不用重打。 */
	private static String search = "";

	public SoundPickerScreen(String currentSoundId, Consumer<String> onPicked, ScreenExtension parent) {
		super(parent);

		AbstractSoundInstanceExtension.iterateSoundIds(identifier -> soundIds.add(identifier.data.toString()));
		Collections.sort(soundIds);
		if (search.isEmpty() && !currentSoundId.isEmpty()) {
			// 带着当前值当搜索词进来，改同一组里的另一条会顺手很多。
			final int slash = currentSoundId.lastIndexOf('/');
			search = slash < 0 ? currentSoundId : currentSoundId.substring(0, slash + 1);
		}

		soundsList = new DashboardList(
				(dashboardListItem, index) -> preview(soundIds.get((int) dashboardListItem.id)),
				null, null, null,
				(dashboardListItem, index) -> {
					onPicked.accept(soundIds.get((int) dashboardListItem.id));
					onClose2();
				},
				null, null,
				() -> search, text -> search = text,
				false
		);
	}

	@Override
	protected void init2() {
		super.init2();
		soundsList.x = (width - PANEL_WIDTH * 2) / 2;
		soundsList.y = SQUARE_SIZE + TEXT_HEIGHT + TEXT_PADDING;
		soundsList.width = PANEL_WIDTH * 2;
		soundsList.height = height - soundsList.y - SQUARE_SIZE;
		soundsList.init(this::addChild);
		refresh();
	}

	@Override
	public void tick2() {
		soundsList.tick();
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		graphicsHolder.drawCenteredText(TextHelper.translatable("gui.yuzimtr.announcer_sound_hint"),
				width / 2, SQUARE_SIZE, ARGB_LIGHT_GRAY);
		soundsList.render(graphicsHolder);
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public void mouseMoved2(double mouseX, double mouseY) {
		soundsList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled2(double mouseX, double mouseY, double amount) {
		soundsList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled2(mouseX, mouseY, amount);
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	private void refresh() {
		final ObjectArrayList<DashboardListItem> items = new ObjectArrayList<>();
		for (int i = 0; i < soundIds.size(); i++) {
			items.add(new DashboardListItem(i, soundIds.get(i), ARGB_BACKGROUND));
		}
		// hasFind = 试听、hasAdd = 选中，其余按钮都不要。
		soundsList.setData(items, true, false, false, false, true, false);
	}

	/**
	 * 试听：在<b>玩家自己耳边</b>放，不走方块坐标。
	 *
	 * <p>MTR 的做法是 {@code playSoundAtBlockCenter(pos, …, 1000000, …)}，用一个荒唐的增益
	 * 绕过距离衰减；既然只是试听，直接贴耳放更干净，也不会因为方块在身后而听着偏。
	 */
	static void preview(String soundId) {
		if (soundId.isEmpty()) {
			return;
		}
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}
		try {
			clientPlayerEntity.playSound(SoundHelper.createSoundEvent(new Identifier(soundId)), 1, 1);
		} catch (Exception ignored) {
			// id 不合法 —— 列表里的都是合法的，只有外部传进来的才可能出问题。
		}
	}
}
