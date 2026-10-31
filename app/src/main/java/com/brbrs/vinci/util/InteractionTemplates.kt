package com.brbrs.vinci.util

/** A quick-fill preset for a recurring kind of interaction (e.g. a recruitment intake call). */
data class InteractionTemplate(
    val name: String,
    val interactionType: String,
    val reason: String,
    val followUpDays: Int = 0, // 0 = no follow-up suggested
)

val INTERACTION_TEMPLATES = listOf(
    InteractionTemplate("Intake call", "Call", "Intro", followUpDays = 3),
    InteractionTemplate("Reference check", "Call", "Support", followUpDays = 0),
    InteractionTemplate("Advisory session", "Meeting", "Planning", followUpDays = 7),
    InteractionTemplate("Follow-up chat", "Call", "Follow-up", followUpDays = 0),
)
