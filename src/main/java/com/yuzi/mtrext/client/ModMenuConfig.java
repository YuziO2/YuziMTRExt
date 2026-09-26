package com.yuzi.mtrext.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.mtr.mapping.holder.Screen;

/**
 * 「模组菜单」的配置界面入口，写法照抄 MTR 自己的 {@code org.mtr.init.ModMenuConfig}。
 *
 * <p>这个类只有在装了「模组菜单」时才会被 classload（modmenu 入口点由它自己去查），
 * 所以 modmenu 是 {@code modCompileOnly}，没装也不会出问题。
 */
public final class ModMenuConfig implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new YuziConfigScreen(new Screen(parent));
	}
}
