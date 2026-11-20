// Ported from internal/httpapi/chat.go at 9a4c36cf.
package com.magichandy.mobile.core.chat

private val stopPhrases =
    setOf(
        "stop",
        "stop motion",
        "stop the motion",
        "pause",
        "pause motion",
        "pause the motion",
        "end",
        "end motion",
        "end the motion",
        "emergency stop",
        "please stop",
        "stop please",
        "please stop motion",
        "please stop the motion",
        "stop motion please",
        "stop the motion please",
        "stop everything",
        "stop moving",
        "please stop moving",
        "stop the device",
        "turn motion off",
        "turn off motion",
        "enough",
        "that s enough",
        "thats enough",
        "para",
        "detén",
        "deten",
        "detener",
        "detén el movimiento",
        "deten el movimiento",
        "detener el movimiento",
        "para el movimiento",
        "detener todo",
        "pausa",
        "pausa el movimiento",
        "parada de emergencia",
        "por favor detén el movimiento",
        "pare",
        "parar",
        "pare o movimento",
        "parar o movimento",
        "parar tudo",
        "pause o movimento",
        "parada de emergência",
        "por favor pare o movimento",
        "停止",
        "停止运动",
        "全部停止",
        "停下",
        "停下运动",
        "暂停",
        "暂停运动",
        "紧急停止",
        "请停止运动",
        "止めて",
        "動きを止めて",
        "モーションを止めて",
        "モーションを停止",
        "停止して",
        "一時停止",
        "すべて停止",
        "緊急停止",
        "動きを止めてください",
    )
private val separators = Regex("[\\p{Z}\\p{P}\\p{S}\\s]+")

internal fun isChatStopMessage(text: String): Boolean =
    text
        .lowercase()
        .replace(separators, " ")
        .trim()
        .removeSuffix(" right now")
        .removeSuffix(" now") in stopPhrases
