package com.example.kakaotalkautobot

import java.util.Locale

/** Only direct questions about this app's engine use metadata instead of generated guesses. */
object ModelIdentity {
    private val directQuestions = setOf(
        "너모델이뭐야", "너는무슨모델이야", "너무슨모델이야", "모델이뭐야", "무슨모델이야",
        "어떤모델이야", "어떤모델을사용해", "무슨모델을사용해", "너어떤모델써",
        "너gpt야", "너chatgpt야", "너챗gpt야", "너챗지피티야", "너gemma야", "너제미나이야",
        "whatmodelareyou", "whichmodelareyou", "whatmodeldoyouuse", "areyouchatgpt", "areyougpt"
    )

    fun replyIfAsked(message: String, modelName: String): String? {
        val question = message.trim().lowercase(Locale.ROOT)
            .replace(Regex("[\\s?？!！.。]+"), "")
        if (question !in directQuestions || modelName.isBlank()) return null
        return "이 앱의 기본 답장 모델은 $modelName 입니다."
    }

    fun promptRule(modelName: String): String =
        "이 앱의 기본 답장 모델: $modelName. 모델 정체성을 묻는 경우 이 정보를 사용하고 다른 모델이라고 지어내지 마라. " +
            "일반 대화에서는 모델 이름을 먼저 언급하지 마라.\n"
}
