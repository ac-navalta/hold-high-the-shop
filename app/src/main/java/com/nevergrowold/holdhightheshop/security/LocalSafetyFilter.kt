package com.nevergrowold.holdhightheshop.security

import java.util.Locale

/**
 * Instant, offline-capable pre-screen for prohibited campus items.
 *
 * Runs BEFORE the live Gemini Flash multimodal safety check so obvious policy
 * violations are stopped immediately even on offline/rate-limited connections.
 */
object LocalSafetyFilter {

    data class Violation(
        val category: String,
        val policySection: String,
        val reason: String,
    )

    private data class Rule(
        val category: String,
        val policySection: String,
        val reason: String,
        val patterns: List<Regex>,
    )

    private val RULES = listOf(
        Rule(
            category = "WEAPONS_OR_DANGEROUS",
            policySection = "UC Marketplace Guidelines §3.1 — Weapons & Dangerous Items",
            reason = "Weapons, blades, tasers, fireworks, and hazardous materials are strictly prohibited on campus.",
            patterns = listOf(
                Regex("\\b(gun|firearm|pistol|revolver|rifle|shotgun|ammo|ammunition|bullets?)\\b"),
                Regex("\\b(balisong|switchblade|butterfly\\s+knife|brass\\s+knuckles?|dagger|machete|bolo\\s+knife|karambit|tactical\\s+knife)\\b"),
                Regex("\\b(stun\\s*gun|taser|pepper\\s+spray|tear\\s+gas|explosive|firecracker|fireworks?|grenade|bomb)\\b"),
            ),
        ),
        Rule(
            category = "ALCOHOL_TOBACCO_VAPE",
            policySection = "UC Marketplace Guidelines §3.2 — Alcohol, Tobacco & Vapes",
            reason = "Vapes, e-cigarettes, nicotine pods, tobacco, and alcoholic beverages are not allowed on the UC campus marketplace.",
            patterns = listOf(
                Regex("\\b(vape|vaping|e-?cig|e-?cigarette|disposable\\s+pod|vape\\s+juice|e-?liquid|relx|flava|shift\\s+vape|nicotine\\s+pouch|zyn)\\b"),
                Regex("\\b(cigarette|marlboro|tobacco|cigar|hookah|shisha)\\b"),
                Regex("\\b(vodka|whiskey|whisky|tequila|soju|gin\\s+bilog|emperador|tanduay|red\\s+horse|san\\s+miguel\\s+beer|liquor|alcohol\\s+bottle|rum\\s+bottle)\\b"),
            ),
        ),
        Rule(
            category = "DRUGS_MEDICATION",
            policySection = "UC Marketplace Guidelines §3.3 — Controlled Substances & Medication",
            reason = "Illegal drugs, cannabis, and prescription medications cannot be sold, lent, or swapped by students.",
            patterns = listOf(
                Regex("\\b(marijuana|cannabis|weed|kush|shabu|methamphetamine|cocaine|ecstasy|mdma|lsd|opioid|fentanyl|bong|weed\\s+pipe)\\b"),
                Regex("\\b(prescription\\s+drug|xanax|adderall|ritalin|oxycodone|tramadol|antibiotics?\\s+for\\s+sale|rx\\s+meds?)\\b"),
            ),
        ),
        Rule(
            category = "ACADEMIC_DISHONESTY",
            policySection = "UC Marketplace Guidelines §3.4 — Academic Integrity",
            reason = "Leaked exam answer keys, paid essay/thesis writing, and homework-for-hire violate UC academic integrity policy.",
            patterns = listOf(
                Regex("\\b(leaked\\s+exam|exam\\s+leak|answer\\s+key\\s+for\\s+sale|test\\s+bank\\s+leak|cheat\\s+sheet\\s+for\\s+exam)\\b"),
                Regex("\\b(ghostwrit(?:e|ing)|do\\s+your\\s+(?:homework|assignment|thesis|capstone)|paid\\s+(?:thesis|essay|assignment)|essay\\s+mill)\\b"),
                Regex("\\b(fake\\s+uc\\s+id|forged\\s+id|fake\\s+medical\\s+certificate|fake\\s+excuse\\s+letter)\\b"),
            ),
        ),
        Rule(
            category = "ADULT_OR_STOLEN",
            policySection = "UC Marketplace Guidelines §3.5 — Prohibited, Stolen & Explicit Content",
            reason = "Adult/explicit content, gambling accounts, and stolen or pirated goods are prohibited on Hold High The Shop.",
            patterns = listOf(
                Regex("\\b(porn|nsfw|onlyfans|sex\\s+toy|vibrator|dildo|escort\\s+service)\\b"),
                Regex("\\b(stolen\\s+(?:phone|laptop|item)|hacked\\s+account|cracked\\s+account|skimmer)\\b"),
            ),
        ),
    )

    /**
     * Scans listing text fields. Returns a [Violation] if a prohibited pattern is matched,
     * or `null` if the local pre-screen finds no clear violation.
     */
    fun check(title: String, description: String, wants: String? = null, meetupNote: String? = null): Violation? {
        val combined = listOfNotNull(title, description, wants, meetupNote)
            .joinToString(" ")
            .lowercase(Locale.US)
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "") // Strip zero-width evasion chars
            .replace(Regex("[^a-z0-9\\s'-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (combined.isEmpty()) return null
        for (rule in RULES) {
            if (rule.patterns.any { it.containsMatchIn(combined) }) {
                return Violation(rule.category, rule.policySection, rule.reason)
            }
        }
        return null
    }
}
