package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class search_query_operator_test {

    @Test
    void supports_common_text_operators() {
        String haystack = "Alpha beta Gamma";

        assertTrue(search_query_operator.CONTAINS.matches(haystack, "beta", false));
        assertTrue(search_query_operator.NOT_CONTAINS.matches(haystack, "delta", false));
        assertTrue(search_query_operator.EQUALS.matches("ExactValue", "ExactValue", true));
        assertTrue(search_query_operator.NOT_EQUALS.matches("ExactValue", "exactvalue", true));
        assertTrue(search_query_operator.STARTS_WITH.matches(haystack, "Alpha", true));
        assertTrue(search_query_operator.ENDS_WITH.matches(haystack, "Gamma", true));
        assertTrue(search_query_operator.ALL_TERMS.matches(haystack, "alpha gamma", false));
        assertTrue(search_query_operator.ANY_TERMS.matches(haystack, "omega beta", false));
        assertTrue(search_query_operator.REGEX.matches(haystack, ".*beta.*", false));

        assertFalse(search_query_operator.CONTAINS.matches(haystack, "BETA", true));
        assertFalse(search_query_operator.ENDS_WITH.matches(haystack, "gamma", true));
        assertFalse(search_query_operator.ALL_TERMS.matches(haystack, "alpha omega", false));
    }
}
