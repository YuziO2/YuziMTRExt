package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.NtePartNameMap;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.resource.ModelPropertiesPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * 让为 NTE 写的资源包在 MTR4 上能对上部件名。详见 {@link NtePartNameMap}。
 *
 * <p>做法：在 OBJ 版 writeCache 的入口把传进来的 map 换成带兜底的包装。
 * 实际查找发生在 {@code names.forEach} 编译出的 synthetic lambda
 * （4.0.5 里是 lambda$writeCache$10）里，那个 lambda 是把 map 当参数收进去的，
 * 所以在 HEAD 替换局部变量就够了 —— 不用去按 lambda 名字注入（编号会随上游改动变化）。
 *
 * <p>注意只包这一处，不去动 ModelResourceLoader.loadModel 返回的那个 map：
 * DynamicVehicleModel 的构造函数会拿它做 {@code addPartsIfEmpty(keySet())}，
 * 往那个 map 里加别名会让没有显式 parts 的模型部件数翻倍、几何体重复渲染。
 */
@Mixin(value = ModelPropertiesPart.class, remap = false)
public class ModelPropertiesPartMixin {

	@ModifyVariable(
			method = "writeCache(Ljava/util/Map;Lorg/mtr/mod/resource/PositionDefinitions;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;D)V",
			at = @At("HEAD"),
			argsOnly = true,
			index = 1
	)
	private Map<String, OptimizedModel.ObjModel> yuzimtr$wrapNameLookup(Map<String, OptimizedModel.ObjModel> nameToObjModels) {
		if (!Config.get().stripNtePartNamePrefix || nameToObjModels instanceof NtePartNameMap) {
			return nameToObjModels;
		}
		return new NtePartNameMap<>(nameToObjModels);
	}
}
