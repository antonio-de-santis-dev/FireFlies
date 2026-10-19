package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import it.fireflies.common.ApiException;

class PasswordPolicyTest {

    @Test
    void fraseLungaAccettata() {
        assertThat(PasswordPolicy.problem("lucciole sul bancone alle sette", "titolare@example.invalid")).isNull();
    }

    @Test
    void troppoCorta() {
        assertThat(PasswordPolicy.problem("Corta1!", "a@example.invalid")).contains("almeno 12");
    }

    @Test
    void oltre72ByteRifiutata() {
        assertThat(PasswordPolicy.problem("è".repeat(40), "a@example.invalid")).contains("72 byte");
    }

    @Test
    void ripetitiva() {
        assertThat(PasswordPolicy.problem("aaaaaaaaaaaaaaaa", "a@example.invalid")).contains("ripetitiva");
    }

    @Test
    void contieneIlNomeDellEmail() {
        assertThat(PasswordPolicy.problem("Mario.Rossi-2030!", "mario.rossi@example.invalid")).contains("email");
    }

    @Test
    void erroreApplicativo422() {
        assertThatThrownBy(() -> PasswordPolicy.check("corta", "a@example.invalid"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("WEAK_PASSWORD"));
    }
}
