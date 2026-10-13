package com.visionnav.core.command

/** A recognised voice command. Parsing never fails: unrecognised speech becomes [Unknown]. */
sealed interface VoiceCommand {
    data class NavigateTo(val query: String) : VoiceCommand
    /** [name] is a canonical saved-place name: "home", "work", or a user-defined name. */
    data class GoToSavedPlace(val name: String) : VoiceCommand
    data object WhereAmI : VoiceCommand
    data object WhatsAround : VoiceCommand
    data object TrafficAssist : VoiceCommand
    /** User reports reaching a refuge island / median: start observing the next road segment. */
    data object NextSegment : VoiceCommand
    /** User confirms they are at the crossing / kerb. */
    data object ConfirmAtCrossing : VoiceCommand
    data object RepeatLast : VoiceCommand
    data object Stop : VoiceCommand
    data object PauseGuidance : VoiceCommand
    data object ResumeGuidance : VoiceCommand
    /** Emergency request. The app must always confirm before contacting anyone. */
    data object Sos : VoiceCommand
    data object HelpCommands : VoiceCommand
    /** [name] is null when the user did not say one ("save this place"); the app should ask. */
    data class SaveCurrentPlace(val name: String?) : VoiceCommand
    data object TorchOn : VoiceCommand
    data object TorchOff : VoiceCommand
    data object SpeechLouder : VoiceCommand
    data object SpeechQuieter : VoiceCommand
    data object SpeechFaster : VoiceCommand
    data object SpeechSlower : VoiceCommand
    data class Unknown(val text: String) : VoiceCommand
}

/**
 * Forgiving English parser from recognised speech to [VoiceCommand].
 *
 * Case-, punctuation- and filler-insensitive ("Um, please take me to the station!" →
 * NavigateTo("station")). Parameterised commands ("take me to X", "save this place as X") are
 * matched first, so place names containing command words ("take me to the bus stop") are not
 * mistaken for commands. Emergency words are honoured anywhere in the utterance except inside a
 * destination ("navigate to the emergency department" is navigation); a bare "help" is treated as
 * SOS because the app always asks for confirmation before contacting anyone.
 *
 * @param savedPlaceNames user-defined saved place names (e.g. "gym"); matched case-insensitively
 *   so "take me to the gym" becomes [VoiceCommand.GoToSavedPlace]. Home and work are built in.
 */
class VoiceCommandParser(savedPlaceNames: Collection<String> = emptyList()) {

    private val customPlaces: Map<String, String> =
        savedPlaceNames.associateBy { normalize(it) }.filterKeys { it.isNotEmpty() }

    /** Parses the best of several recognizer hypotheses (ordered most to least confident). */
    /** The parser's own normalisation (lower case, contractions expanded, punctuation removed), for feature skills. */
    fun normalized(text: String): String = normalize(text)

    fun parse(alternatives: List<String>): VoiceCommand {
        var firstUnknown: VoiceCommand? = null
        for (alt in alternatives) {
            val cmd = parse(alt)
            if (cmd !is VoiceCommand.Unknown) return cmd
            if (firstUnknown == null) firstUnknown = cmd
        }
        return firstUnknown ?: VoiceCommand.Unknown("")
    }

    fun parse(text: String): VoiceCommand {
        val cleaned = stripFillers(normalize(text))
        if (cleaned.isEmpty()) return VoiceCommand.Unknown(text)
        return parseCleaned(cleaned) ?: fallback(cleaned) ?: VoiceCommand.Unknown(text)
    }

    private fun parseCleaned(s: String): VoiceCommand? {
        exact(s)?.let { return it }
        parseSave(s)?.let { return it }
        parseNavigate(s)?.let { return it }
        parseHelpMe(s)?.let { return it }
        if (EMERGENCY.containsMatchIn(s)) return VoiceCommand.Sos
        return patterns(s)
    }

    // --- parameterised -------------------------------------------------------------------

    private fun parseSave(s: String): VoiceCommand? {
        SAVE_WITH_NAME.matchEntire(s)?.let { m ->
            val name = cleanName(m.groupValues.last())
            return VoiceCommand.SaveCurrentPlace(name.ifEmpty { null })
        }
        if (SAVE_NO_NAME.matches(s)) return VoiceCommand.SaveCurrentPlace(null)
        return null
    }

    private fun parseNavigate(s: String): VoiceCommand? {
        GO_HOME.matchEntire(s)?.let { return VoiceCommand.GoToSavedPlace(HOME) }
        for (prefix in NAVIGATE_PREFIXES) {
            if (s.startsWith("$prefix ")) {
                val query = cleanName(s.removePrefix("$prefix "))
                if (query.isEmpty()) return null
                savedPlace(query)?.let { return VoiceCommand.GoToSavedPlace(it) }
                return VoiceCommand.NavigateTo(query)
            }
        }
        return null
    }

    /** "help me find the station" → navigation; "help me cross" → traffic; "help me" → SOS. */
    private fun parseHelpMe(s: String): VoiceCommand? {
        if (!s.startsWith("help me ")) return null
        val rest = s.removePrefix("help me ")
        if (rest.startsWith("find ") || rest.startsWith("get to ") || rest.startsWith("go to ")) {
            return parseNavigate(rest)
        }
        if (CROSS_HELP.matches(rest)) return VoiceCommand.TrafficAssist
        return null
    }

    private fun savedPlace(query: String): String? {
        val hasMy = query.startsWith("my ")
        val q = query.removePrefix("my ").trim()
        return when {
            q == "home" || (hasMy && q in HOME_SYNONYMS) -> HOME
            q in WORK_SYNONYMS -> WORK
            else -> customPlaces[query] ?: customPlaces[q]
        }
    }

    // --- fixed phrases -------------------------------------------------------------------

    private fun exact(s: String): VoiceCommand? = EXACT[s]

    private fun patterns(s: String): VoiceCommand? {
        for ((regex, cmd) in PATTERNS) if (regex.containsMatchIn(s)) return cmd
        return null
    }

    private fun fallback(s: String): VoiceCommand? {
        val tokens = s.split(' ')
        if ("help" in tokens) return VoiceCommand.Sos
        return null
    }

    // --- normalisation -------------------------------------------------------------------

    private fun cleanName(raw: String): String {
        var words = raw.trim().split(' ').filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in LEADING_ARTICLES) words = words.drop(1)
        while (words.isNotEmpty() && words.last() in TRAILING_FILLERS) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    private fun stripFillers(s: String): String {
        var words = s.split(' ').filter { it.isNotEmpty() && it !in ANYWHERE_FILLERS }
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            for (phrase in LEADING_FILLER_PHRASES) {
                if (words.size > phrase.size && words.subList(0, phrase.size) == phrase) {
                    words = words.drop(phrase.size)
                    changed = true
                }
            }
            if (words.size > 1 && words.last() in TRAILING_FILLERS) {
                words = words.dropLast(1)
                changed = true
            }
        }
        return words.joinToString(" ")
    }

    private companion object {
        const val HOME = "home"
        const val WORK = "work"
        val HOME_SYNONYMS = setOf("house", "place", "flat", "apartment")
        val WORK_SYNONYMS = setOf("work", "office", "workplace")

        fun normalize(text: String): String {
            var s = text.lowercase()
                .replace('’', '\'')
                .replace('‘', '\'')
            for ((from, to) in CONTRACTIONS) s = s.replace(from, to)
            s = s.replace(Regex("[^a-z0-9' ]"), " ").replace("'", "")
            return s.replace(Regex("\\s+"), " ").trim()
        }

        val CONTRACTIONS = listOf(
            "what's" to "what is", "where's" to "where is", "i'm" to "i am", "let's" to "let us",
            "i've" to "i have", "i'd" to "i would", "it's" to "it is", "that's" to "that is",
            "can't" to "cannot", "don't" to "do not", "thank you" to "thanks",
        )

        /** Hesitations removed anywhere in the utterance. */
        val ANYWHERE_FILLERS = setOf("um", "umm", "uh", "uhh", "er", "erm", "hmm", "ah", "please")

        val LEADING_FILLER_PHRASES: List<List<String>> = listOf(
            "ok google", "hey google", "hey visionnav", "hey vision nav", "visionnav", "vision nav",
            "okay", "ok", "hey", "so", "well", "now", "and", "oh", "alright", "right",
            "could you", "can you", "would you", "will you", "kindly", "just",
            "i want you to", "i would like you to", "i need you to",
        ).map { it.split(' ') }

        /** Politeness/hesitation words dropped from the end ("thank you" is folded to "thanks" first). */
        val TRAILING_FILLERS = setOf("please", "now", "thanks", "ok", "okay")

        val LEADING_ARTICLES = setOf("the", "a", "an", "some")

        val NAVIGATE_PREFIXES = listOf(
            "i want to go to", "i would like to go to", "i need to go to", "i need to get to",
            "i want to get to", "how do i get to", "how can i get to", "show me the way to",
            "give me directions to", "get directions to", "get me directions to", "directions to",
            "find the way to", "find a route to", "route to", "navigate me to", "navigate to",
            "take me to", "bring me to", "lead me to", "guide me to", "walk me to", "get me to",
            "let us go to", "go to", "walk to", "head to", "head for", "find me", "find",
            "search for", "look for", "where is the nearest", "where is the closest",
        ).sortedByDescending { it.length }

        val GO_HOME = Regex("(take me|go|walk me|bring me|head|get me|i want to go|lets go|let us go|navigate) home")

        val SAVE_WITH_NAME = Regex(
            "(save|remember|mark|store|add) (this|the current|my current|current|this current|my)? ?" +
                "(place|location|spot|position|point)? ?(as|called|named) (.+)",
        )
        val SAVE_NO_NAME = Regex(
            "(save|remember|mark|store) (this|the current|my current|current|my)? ?(place|location|spot|position|point)( here)?|save here",
        )

        val CROSS_HELP = Regex("(to )?cross( the)?( road| street)?( here)?")

        val EMERGENCY = Regex("\\b(emergency|sos|s o s|mayday|call an ambulance|call the police|i am hurt|i have fallen|i fell)\\b")

        private fun cmds(cmd: VoiceCommand, vararg phrases: String) = phrases.map { it to cmd }

        val EXACT: Map<String, VoiceCommand> = (
            cmds(VoiceCommand.HelpCommands, "what can i say", "what can you do", "help commands", "commands",
                "list commands", "voice commands", "what are the commands", "show commands", "how does this work",
                "how do i use this", "what commands are there") +
                cmds(VoiceCommand.Sos, "help", "help me", "help help", "i need help", "need help", "get help",
                    "call for help", "send help") +
                cmds(VoiceCommand.Stop, "stop", "cancel", "end", "quit", "stop it", "end navigation", "stop navigation",
                    "cancel navigation", "end route", "cancel route", "stop route", "stop guidance", "end guidance",
                    "exit navigation", "quit navigation", "finish navigation", "never mind", "nevermind",
                    "stop navigating", "cancel that", "cancel it") +
                cmds(VoiceCommand.PauseGuidance, "pause", "pause navigation", "pause guidance", "pause directions",
                    "hold on", "wait", "hold", "pause route") +
                cmds(VoiceCommand.ResumeGuidance, "resume", "resume navigation", "resume guidance", "continue navigation",
                    "continue guidance", "carry on", "unpause", "continue", "resume route", "keep going",
                    "resume directions") +
                cmds(VoiceCommand.RepeatLast, "repeat", "repeat that", "repeat last", "say again", "say that again",
                    "pardon", "come again", "again", "what did you say", "sorry what", "repeat please", "one more time",
                    "repeat the last instruction", "repeat instruction") +
                cmds(VoiceCommand.WhereAmI, "where am i", "where are we", "what is my location", "my location",
                    "current location", "what street am i on", "what street is this", "where is this", "location",
                    "what road am i on", "where am i now", "tell me where i am", "whereabouts am i") +
                cmds(VoiceCommand.WhatsAround, "what is around", "what is around me", "what is nearby", "whats around",
                    "describe", "describe surroundings", "describe my surroundings", "describe the scene",
                    "describe the area", "what do you see", "what can you see", "look around", "scan",
                    "surroundings", "what is in front of me", "what is ahead", "what is near me", "what is here") +
                cmds(VoiceCommand.TrafficAssist, "traffic", "check traffic", "crossing assist", "observe traffic",
                    "traffic assist", "watch traffic", "watch the traffic", "check the traffic", "traffic check",
                    "crossing", "crossing help", "assist crossing", "help crossing", "traffic mode", "listen to traffic",
                    "observe the traffic", "start traffic assist") +
                cmds(VoiceCommand.NextSegment, "next segment", "next road", "next", "next part", "next lane",
                    "next section", "other side", "next crossing segment") +
                cmds(VoiceCommand.TorchOn, "light on", "torch on", "flashlight on", "light") +
                cmds(VoiceCommand.TorchOff, "light off", "torch off", "flashlight off", "lights off") +
                cmds(VoiceCommand.SpeechLouder, "louder", "speak louder", "talk louder", "volume up", "increase volume",
                    "turn it up", "turn up the volume", "more volume", "speak up") +
                cmds(VoiceCommand.SpeechQuieter, "quieter", "softer", "speak softer", "speak quieter", "talk quieter",
                    "volume down", "lower volume", "decrease volume", "turn it down", "turn down the volume", "less volume",
                    "lower the volume") +
                cmds(VoiceCommand.SpeechFaster, "faster", "speak faster", "talk faster", "speed up", "speech faster",
                    "speak more quickly", "quicker", "speak quicker") +
                cmds(VoiceCommand.SpeechSlower, "slower", "speak slower", "talk slower", "slow down", "speech slower",
                    "speak more slowly", "slow")
            ).toMap()

        /** Looser containment patterns, tried in order after exact matches and prefixes. */
        val PATTERNS: List<Pair<Regex, VoiceCommand>> = listOf(
            Regex("\\b(what can i say|what are (the|my) (voice )?commands|list (of )?commands)\\b") to VoiceCommand.HelpCommands,
            Regex("\\b(at|on|reached|reach|made it to|on to|onto) the (traffic |refuge |pedestrian )?(island|median|refuge|middle( of the road)?|central reservation)\\b") to VoiceCommand.NextSegment,
            Regex("\\bnext (segment|road|lane|section|part)\\b") to VoiceCommand.NextSegment,
            Regex("\\b(at|reached|reach|made it to|by) the (kerb|curb|crossing|crosswalk|zebra( crossing)?|pedestrian crossing|road edge|edge of the road)\\b") to VoiceCommand.ConfirmAtCrossing,
            Regex("\\b(i am|we are) (at|by|on) (the )?(kerb|curb|kerbside|curbside)\\b") to VoiceCommand.ConfirmAtCrossing,
            Regex("\\b(where am i|where are we|my (current )?location|what street)\\b") to VoiceCommand.WhereAmI,
            Regex("\\b(what is|whats) (around|nearby|near me|in front|ahead)\\b|\\bdescribe\\b|\\bwhat (do|can) you see\\b") to VoiceCommand.WhatsAround,
            Regex("\\b(check|observe|watch|monitor|listen (to|for))( the)? traffic\\b|\\btraffic (assist|check|mode|help)\\b|\\bcrossing (assist|help|mode)\\b") to VoiceCommand.TrafficAssist,
            Regex("\\b(turn|switch|put) (on )?(the )?(light|torch|flashlight)( on)?\\b(?!.*\\boff\\b)") to VoiceCommand.TorchOn,
            Regex("\\b(light|torch|flashlight) on\\b") to VoiceCommand.TorchOn,
            Regex("\\b(turn|switch|put) (off )?(the )?(light|torch|flashlight)s?( off)?\\b") to VoiceCommand.TorchOff,
            Regex("\\b(light|torch|flashlight)s? off\\b") to VoiceCommand.TorchOff,
            Regex("\\b(repeat|say (that|it) again)\\b") to VoiceCommand.RepeatLast,
            Regex("\\b(pause) (the )?(navigation|guidance|directions|route)\\b") to VoiceCommand.PauseGuidance,
            Regex("\\b(resume|continue|restart) (the )?(navigation|guidance|directions|route)\\b") to VoiceCommand.ResumeGuidance,
            Regex("\\b(stop|cancel|end|quit|exit|finish) (the )?(navigation|guidance|directions|route|navigating)\\b") to VoiceCommand.Stop,
            Regex("\\b(speak|talk|voice|speech) (a bit |a little )?(louder|up)\\b|\\bvolume up\\b") to VoiceCommand.SpeechLouder,
            Regex("\\b(speak|talk|voice|speech) (a bit |a little )?(quieter|softer)\\b|\\bvolume down\\b") to VoiceCommand.SpeechQuieter,
            Regex("\\b(speak|talk|voice|speech) (a bit |a little )?(faster|quicker|more quickly)\\b") to VoiceCommand.SpeechFaster,
            Regex("\\b(speak|talk|voice|speech) (a bit |a little )?(slower|more slowly)\\b") to VoiceCommand.SpeechSlower,
        )
    }
}
