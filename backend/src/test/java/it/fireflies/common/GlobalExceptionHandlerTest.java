package it.fireflies.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Contratto degli errori (API_CONTRACT §1): problem+json, codice stabile, nessun dettaglio interno. */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    record Probe(@NotBlank String name, @Min(1) @Max(50) int guests) {
    }

    @RestController
    static class ProbeController {

        @PostMapping("/probe")
        Probe echo(@Valid @RequestBody Probe probe) {
            return probe;
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_EXCEEDED", "Posti esauriti", "Restano 2 posti.",
                    Map.of("remainingCovers", 2));
        }

        @GetMapping("/probe/table")
        void table() {
            throw new DataIntegrityViolationException("x", new PSQLException(new ServerErrorMessage(
                    "SERROR\0C23P01\0Mconflicting key value violates exclusion constraint\0nex_table_no_overlap\0")));
        }

        @GetMapping("/probe/unknown-constraint")
        void unknownConstraint() {
            throw new DataIntegrityViolationException("x", new PSQLException(new ServerErrorMessage(
                    "SERROR\0C23514\0Mnew row violates check constraint\0nck_something_internal\0")));
        }

        @GetMapping("/probe/lock-conflict")
        void deadlock() {
            throw new org.springframework.dao.PessimisticLockingFailureException("deadlock detected");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("password=segreta nel messaggio interno");
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void jsonMalformatoDa400() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{ non json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.type").value("urn:fireflies:problem:malformed-request"));
    }

    @Test
    void validazioneDa422ConErroriPerCampo() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"guests\":80}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'guests')].code").value("MAX"));
    }

    @Test
    void erroreApplicativoConContesto() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.title").value("Posti esauriti"))
                .andExpect(jsonPath("$.context.remainingCovers").value(2));
    }

    @Test
    void vincoloTavoloTradottoSenzaNomeInterno() throws Exception {
        mvc.perform(get("/probe/table"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ex_table"))));
    }

    @Test
    void vincoloSconosciutoGenerico() throws Exception {
        mvc.perform(get("/probe/unknown-constraint"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ck_something"))));
    }

    @Test
    void conflittoDiLockRipetibile() throws Exception {
        mvc.perform(get("/probe/lock-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deadlock"))));
    }

    @Test
    void erroreImprevistoNonEsponeDettagli() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("segreta"))));
    }

    @Test
    void percorsoInesistenteDa404ConCodice() throws Exception {
        mvc.perform(get("/non-esiste"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("La risorsa richiesta non esiste."));
    }

    @Test
    void metodoNonAmmessoDa405ConCodice() throws Exception {
        mvc.perform(post("/probe/conflict"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
