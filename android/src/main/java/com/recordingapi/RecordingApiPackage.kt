package com.recordingapi

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import java.util.HashMap

class RecordingApiPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == RecordingApiModule.NAME) {
      RecordingApiModule(reactContext)
    } else {
      null
    }
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      RecordingApiModule.NAME to ReactModuleInfo(
        RecordingApiModule.NAME,
        RecordingApiModule.NAME,
        false,
        false,
        false,
        true
      )
    )
  }
}
