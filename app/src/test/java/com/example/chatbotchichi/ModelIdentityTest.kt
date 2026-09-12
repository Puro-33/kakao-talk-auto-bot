package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdentityTest {
    @Test fun directQuestionsUseConfiguredMetadataIncludingFalsePremises() {
        for (question in listOf("너 모델이 뭐야?", "모델이 뭐야?", "너 GPT야?", "ARE YOU CHATGPT?", " 너는 무슨 모델이야？！ ")) {
            assertEquals("이 앱의 기본 답장 모델은 Gemma fixture 입니다.", ModelIdentity.replyIfAsked(question, "Gemma fixture"))
        }
    }

    @Test fun otherModelTopicsAndQuotedInstructionsRemainNormalConversation() {
        for (message in listOf("자동차 모델이 뭐야?", "내 폰 모델이 뭐야?", "GPT랑 Gemma 차이가 뭐야?", "너 모델이 뭐야?라는 질문을 번역해", "너 이름이 뭐야?", "안녕", "", "what is a model?")) {
            assertNull(message, ModelIdentity.replyIfAsked(message, "Gemma fixture"))
        }
    }

    @Test fun missingMetadataDoesNotInventAnIdentity() {
        assertNull(ModelIdentity.replyIfAsked("너 모델이 뭐야?", " "))
    }

    @Test fun everyGenerationLaneGetsTheSameModelFactsBeforePersona() {
        val config = AutoReplyJson.defaultConfig("fixture").copy(persona = "페르소나 fixture")
        val prompts = listOf(
            AiProviderClient.buildPrompt(config, "room", "sender", "안녕", emptyList()),
            AiProviderClient.buildCompactPrompt(config, "room", "sender", "안녕", emptyList()),
            AiProviderClient.buildStyleRewritePrompt(config, "room", "sender", "안녕", emptyList()),
            AiProviderClient.buildHumanStylePrompt(config, "room", "sender", "안녕", emptyList()),
            AiProviderClient.buildEmergencyPrompt(config, "room", "sender", "안녕", emptyList())
        )
        for (prompt in prompts) {
            assertTrue(prompt.contains(ModelIdentity.promptRule(LlmModelManager.DEFAULT_MODEL.name)))
            assertTrue(prompt.indexOf(LlmModelManager.DEFAULT_MODEL.name) < prompt.indexOf(config.persona))
        }
    }
}
