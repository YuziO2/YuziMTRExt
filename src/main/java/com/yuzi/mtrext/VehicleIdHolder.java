package com.yuzi.mtrext;

/**
 * 鸭子类型接口，由 ModelPropertiesMixin 实现。
 *
 * <p>ModelProperties 自己不知道属于哪辆车，但 VehicleModel.createModel
 * 同时拿着 ModelProperties 和 id，所以在那里把 id 贴上来。
 */
public interface VehicleIdHolder {

	void yuzimtr$setVehicleId(String vehicleId);

	String yuzimtr$getVehicleId();
}
