package com.globespeak.shared

object Bridge {
  // Channel for PCM16 streaming
  const val PATH_AUDIO_PCM16 = "/audio/pcm16"

  // Message paths
  const val PATH_CONTROL_HANDSHAKE = "/control/handshake"
  const val PATH_CONTROL_HEARTBEAT = "/control/heartbeat"
  const val PATH_TEXT_OUT = "/text/out"

  // DataClient keys/paths
  const val PATH_SETTINGS_TARGET_LANG = "/settings/target_lang"
  const val PATH_SETTINGS_REQUEST = "/settings/request"
  const val PATH_ENGINE_STATE = "/engine/state"
  const val DS_TARGET_LANG = "target_lang_bcp47"
  const val DS_TRANSLATION_ENGINE = "translation_engine" // "standard" | "advanced"
  const val DEFAULT_TARGET_LANG = "fr"
}
