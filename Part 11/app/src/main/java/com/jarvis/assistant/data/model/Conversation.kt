package com.jarvis.assistant.data.model

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New chat",
    var pinned: Boolean = false,
    var updatedAt: Long = System.currentTimeMillis(),
    var turns: List<ChatTurn> = emptyList()
)

data class ConversationInfo(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val updatedAt: Long,
    val turnCount: Int,
    val preview: String
)

data class StoredChats(
    val activeId: String = "",
    val conversations: List<Conversation> = emptyList()
)
