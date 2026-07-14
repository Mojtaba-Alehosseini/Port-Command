package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static description of one of the four canonical berths. {@code berthId} must
 * match {@code berth_[1-4]}; physical limits mirror the {@code default/3} facts
 * in {@code port_ontology.pl}.
 */
public record BerthSpec(
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("berth_type") String berthType,
        @JsonProperty("max_draft") double maxDraft,
        @JsonProperty("max_length") double maxLength,
        @JsonProperty("has_crane") boolean hasCrane) {

    private static final Pattern BERTH_ID = Pattern.compile("^berth_[1-4]$");

    public BerthSpec {
        Objects.requireNonNull(berthId, "berthId");
        if (!BERTH_ID.matcher(berthId).matches()) {
            throw new IllegalArgumentException("berthId must match berth_[1-4], got " + berthId);
        }
        if (maxDraft <= 0) {
            throw new IllegalArgumentException("maxDraft must be > 0, got " + maxDraft);
        }
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be > 0, got " + maxLength);
        }
    }
}
