package it.unige.portcommand.nlp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entity extracted by Rasa's DIET classifier from a {@code /model/parse} response.
 * Unrecognised JSON fields (e.g. {@code confidence_entity}, {@code extractor}) are ignored —
 * this task only needs entity/value/span.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityHit(
        @JsonProperty("entity") String entity,
        @JsonProperty("value") String value,
        @JsonProperty("start") int start,
        @JsonProperty("end") int end) {
}
