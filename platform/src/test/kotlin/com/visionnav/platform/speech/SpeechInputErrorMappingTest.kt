package com.visionnav.platform.speech

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeechInputErrorMappingTest {

    @Test
    fun `recogniser errors map to results`() {
        assertThat(SpeechInput.mapError(SpeechRecognizer.ERROR_NO_MATCH)).isEqualTo(SpeechResult.NoMatch)
        assertThat(SpeechInput.mapError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)).isEqualTo(SpeechResult.NoMatch)
        assertThat(SpeechInput.mapError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo(SpeechResult.PermissionDenied)
        assertThat(SpeechInput.mapError(SpeechRecognizer.ERROR_NETWORK))
            .isEqualTo(SpeechResult.Error(SpeechRecognizer.ERROR_NETWORK))
    }
}
