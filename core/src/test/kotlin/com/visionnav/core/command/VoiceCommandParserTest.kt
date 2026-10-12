package com.visionnav.core.command

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.command.VoiceCommand.ConfirmAtCrossing
import com.visionnav.core.command.VoiceCommand.GoToSavedPlace
import com.visionnav.core.command.VoiceCommand.HelpCommands
import com.visionnav.core.command.VoiceCommand.NavigateTo
import com.visionnav.core.command.VoiceCommand.NextSegment
import com.visionnav.core.command.VoiceCommand.PauseGuidance
import com.visionnav.core.command.VoiceCommand.RepeatLast
import com.visionnav.core.command.VoiceCommand.ResumeGuidance
import com.visionnav.core.command.VoiceCommand.SaveCurrentPlace
import com.visionnav.core.command.VoiceCommand.Sos
import com.visionnav.core.command.VoiceCommand.SpeechFaster
import com.visionnav.core.command.VoiceCommand.SpeechLouder
import com.visionnav.core.command.VoiceCommand.SpeechQuieter
import com.visionnav.core.command.VoiceCommand.SpeechSlower
import com.visionnav.core.command.VoiceCommand.Stop
import com.visionnav.core.command.VoiceCommand.TorchOff
import com.visionnav.core.command.VoiceCommand.TorchOn
import com.visionnav.core.command.VoiceCommand.TrafficAssist
import com.visionnav.core.command.VoiceCommand.Unknown
import com.visionnav.core.command.VoiceCommand.WhatsAround
import com.visionnav.core.command.VoiceCommand.WhereAmI
import org.junit.Test

class VoiceCommandParserTest {
    private val parser = VoiceCommandParser(savedPlaceNames = listOf("Gym", "Mum's House"))

    private val cases: List<Pair<String, VoiceCommand>> = listOf(
        // Navigation
        "Take me to the station" to NavigateTo("station"),
        "um please take me to the station" to NavigateTo("station"),
        "Navigate to Anna Salai." to NavigateTo("anna salai"),
        "go to Central Station" to NavigateTo("central station"),
        "Directions to the library, please" to NavigateTo("library"),
        "Get directions to Marina Beach" to NavigateTo("marina beach"),
        "find a pharmacy" to NavigateTo("pharmacy"),
        "Find me a coffee shop" to NavigateTo("coffee shop"),
        "How do I get to the post office?" to NavigateTo("post office"),
        "Could you take me to the bus stop" to NavigateTo("bus stop"),
        "navigate to the emergency department" to NavigateTo("emergency department"),
        "where is the nearest ATM" to NavigateTo("atm"),
        "help me find the station" to NavigateTo("station"),
        "OK so take me to the market now" to NavigateTo("market"),
        // Saved places
        "Take me home" to GoToSavedPlace("home"),
        "go home" to GoToSavedPlace("home"),
        "Go to work" to GoToSavedPlace("work"),
        "navigate to my office" to GoToSavedPlace("work"),
        "take me to my house" to GoToSavedPlace("home"),
        "take me to the gym" to GoToSavedPlace("Gym"),
        "go to mum's house" to GoToSavedPlace("Mum's House"),
        // Where am I / around
        "Where am I?" to WhereAmI,
        "where am i now" to WhereAmI,
        "Can you tell me where I am" to WhereAmI,
        "what street am I on" to WhereAmI,
        "What's around?" to WhatsAround,
        "what's around me" to WhatsAround,
        "Describe" to WhatsAround,
        "describe my surroundings please" to WhatsAround,
        "What do you see" to WhatsAround,
        // Traffic & crossing
        "traffic" to TrafficAssist,
        "Check traffic." to TrafficAssist,
        "crossing assist" to TrafficAssist,
        "observe traffic" to TrafficAssist,
        "please watch the traffic for me" to TrafficAssist,
        "help me cross the road" to TrafficAssist,
        "next segment" to NextSegment,
        "I'm at the island" to NextSegment,
        "I am on the median" to NextSegment,
        "at the median" to NextSegment,
        "next road" to NextSegment,
        "I'm at the crossing" to ConfirmAtCrossing,
        "I'm at the kerb" to ConfirmAtCrossing,
        "at the curb" to ConfirmAtCrossing,
        // Control
        "repeat" to RepeatLast,
        "say that again" to RepeatLast,
        "Sorry, what?" to RepeatLast,
        "could you repeat that please" to RepeatLast,
        "Stop" to Stop,
        "stop now" to Stop,
        "cancel" to Stop,
        "End navigation" to Stop,
        "please stop the navigation" to Stop,
        "pause" to PauseGuidance,
        "pause guidance" to PauseGuidance,
        "resume" to ResumeGuidance,
        "resume navigation" to ResumeGuidance,
        "carry on" to ResumeGuidance,
        // Help / SOS
        "help" to Sos,
        "Help!" to Sos,
        "HELP ME" to Sos,
        "emergency" to Sos,
        "SOS" to Sos,
        "this is an emergency" to Sos,
        "I need help" to Sos,
        "What can I say?" to HelpCommands,
        "what commands are there" to HelpCommands,
        // Save place
        "save this place as Gym" to SaveCurrentPlace("gym"),
        "Save this location as my favourite cafe" to SaveCurrentPlace("my favourite cafe"),
        "remember this place as the bakery" to SaveCurrentPlace("bakery"),
        "save this place" to SaveCurrentPlace(null),
        // Torch
        "light on" to TorchOn,
        "turn on the torch" to TorchOn,
        "turn the light on" to TorchOn,
        "flashlight on please" to TorchOn,
        "light off" to TorchOff,
        "turn off the light" to TorchOff,
        "switch the torch off" to TorchOff,
        // Speech
        "louder" to SpeechLouder,
        "speak up" to SpeechLouder,
        "volume up" to SpeechLouder,
        "quieter" to SpeechQuieter,
        "turn it down" to SpeechQuieter,
        "speak faster" to SpeechFaster,
        "faster please" to SpeechFaster,
        "slower" to SpeechSlower,
        "slow down" to SpeechSlower,
        "can you speak a bit slower" to SpeechSlower,
    )

    @Test
    fun `utterance table`() {
        assertThat(cases.size).isAtLeast(30)
        for ((text, expected) in cases) {
            assertWithMessage("\"$text\"").that(parser.parse(text)).isEqualTo(expected)
        }
    }

    @Test
    fun `unrecognised speech is unknown with original text`() {
        assertThat(parser.parse("the weather is nice today")).isEqualTo(Unknown("the weather is nice today"))
        assertThat(parser.parse("")).isEqualTo(Unknown(""))
        assertThat(parser.parse("   um   ")).isEqualTo(Unknown("   um   "))
        assertThat(parser.parse("take me to")).isInstanceOf(Unknown::class.java)
    }

    @Test
    fun `place names containing command words are not hijacked`() {
        assertThat(parser.parse("take me to the bus stop")).isEqualTo(NavigateTo("bus stop"))
        assertThat(parser.parse("navigate to Light Street")).isEqualTo(NavigateTo("light street"))
        assertThat(parser.parse("go to the traffic police station")).isEqualTo(NavigateTo("traffic police station"))
    }

    @Test
    fun `n-best picks the first non-unknown hypothesis`() {
        assertThat(parser.parse(listOf("where mi", "where am I", "wear a mi"))).isEqualTo(WhereAmI)
        assertThat(parser.parse(listOf("take me to the station", "take me to the nation"))).isEqualTo(NavigateTo("station"))
        assertThat(parser.parse(listOf("traffic", "trafic"))).isEqualTo(TrafficAssist)
        assertThat(parser.parse(listOf("blah blah", "the cat sat", "stop"))).isEqualTo(Stop)
        assertThat(parser.parse(listOf("blah blah", "the cat sat"))).isEqualTo(Unknown("blah blah"))
        assertThat(parser.parse(emptyList())).isEqualTo(Unknown(""))
    }
}
