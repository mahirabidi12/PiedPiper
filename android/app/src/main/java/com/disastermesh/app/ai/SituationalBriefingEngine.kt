package com.disastermesh.app.ai

import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

object SituationalBriefingEngine {

    fun query(
        question: String,
        db: AppDatabase,
        role: Role,
        nodeId: String
    ): Flow<String> = flow {
        val context = SituationalContextAggregator.compile(db, role, nodeId)
        val prompt  = PromptTemplates.sitrepPrompt(context, question)
        emitAll(
            GemmaClient.generateStream(
                prompt            = prompt,
                systemInstruction = PromptTemplates.SITREP_SYSTEM_INSTRUCTION
            )
        )
    }
}
