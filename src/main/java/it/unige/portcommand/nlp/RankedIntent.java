package it.unige.portcommand.nlp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of Rasa's {@code intent_ranking} list in a {@code /model/parse} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RankedIntent(
        @JsonProperty("name") String name,
        @JsonProperty("confidence") double confidence) {
}
