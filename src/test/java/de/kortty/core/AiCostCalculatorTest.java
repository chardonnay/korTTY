package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class AiCostCalculatorTest {

    private static AiProfile priced(Double prompt, Double completion) {
        AiProfile profile = new AiProfile();
        profile.setId("p");
        profile.setPricePerMillionPromptTokens(prompt);
        profile.setPricePerMillionCompletionTokens(completion);
        return profile;
    }

    @Test
    void costsPromptAndCompletionAtTheirOwnPrice() {
        AiProfile profile = priced(3.0, 15.0);

        double cost = AiCostCalculator.cost(profile, new AiTokenUsage(1_000_000, 200_000, 1_200_000));

        assertThat(cost).isWithin(1e-9).of(3.0 + 3.0);
        assertThat(AiCostCalculator.hasPrice(profile)).isTrue();
    }

    @Test
    void profileWithoutPriceCostsNothing() {
        AiProfile profile = priced(null, null);

        assertThat(AiCostCalculator.hasPrice(profile)).isFalse();
        assertThat(AiCostCalculator.cost(profile, 5_000, 5_000)).isEqualTo(0.0);
        assertThat(AiCostCalculator.cost(null, 5_000, 5_000)).isEqualTo(0.0);
    }

    @Test
    void currencyDefaultsToEuro() {
        assertThat(AiCostCalculator.currency(priced(1.0, 1.0))).isEqualTo("EUR");
        AiProfile usd = priced(1.0, 1.0);
        usd.setPriceCurrency(" usd ");
        assertThat(AiCostCalculator.currency(usd)).isEqualTo("USD");
    }

    @Test
    void embeddedAndLoopbackProfilesAreLocal() {
        AiProfile embedded = new AiProfile();
        embedded.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        AiProfile lmStudio = new AiProfile();
        lmStudio.setConnectionMode(AiConnectionMode.HTTP_API);
        lmStudio.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        AiProfile cloud = new AiProfile();
        cloud.setConnectionMode(AiConnectionMode.HTTP_API);
        cloud.setApiUrl("https://api.example.com/v1/chat/completions");

        assertThat(AiCostCalculator.isLocal(embedded)).isTrue();
        assertThat(AiCostCalculator.isLocal(lmStudio)).isTrue();
        assertThat(AiCostCalculator.isLocal(cloud)).isFalse();
        assertThat(AiCostCalculator.isLocal(null)).isFalse();
    }

    @Test
    void tinyPositiveAmountsNeverLookFree() {
        String formatted = AiCostCalculator.format(0.0004, "EUR", Locale.GERMANY);

        assertThat(formatted).startsWith("< ");
        assertThat(formatted).contains("0,01");
        assertThat(AiCostCalculator.format(1.5, "EUR", Locale.GERMANY)).contains("1,50");
    }

    @Test
    void parsesPricesWithEitherDecimalSeparator() {
        assertThat(AiCostCalculator.parsePrice("2,50")).isEqualTo(2.5);
        assertThat(AiCostCalculator.parsePrice("2.50")).isEqualTo(2.5);
        assertThat(AiCostCalculator.parsePrice("1.234,5")).isEqualTo(1234.5);
        assertThat(AiCostCalculator.parsePrice("1,234.5")).isEqualTo(1234.5);
        assertThat(AiCostCalculator.parsePrice(" ")).isNull();
        assertThat(AiCostCalculator.parsePrice("-1")).isNull();
        assertThat(AiCostCalculator.parsePrice("abc")).isNull();
    }

    @Test
    void formatsStoredPriceForTheInputField() {
        assertThat(AiCostCalculator.formatPrice(2.5, Locale.GERMANY)).isEqualTo("2,5");
        assertThat(AiCostCalculator.formatPrice(null, Locale.GERMANY)).isEmpty();
    }
}
