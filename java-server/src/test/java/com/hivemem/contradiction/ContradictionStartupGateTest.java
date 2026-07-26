package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hivemem.queen.QueenProperties;
import org.junit.jupiter.api.Test;

class ContradictionStartupGateTest {

    @Test
    void failsFastWhenQueenIsDisabled() {
        assertThatThrownBy(() -> ContradictionStartupGate.verify(true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hivemem.queen.enabled");
    }

    @Test
    void allowsBothEnabled() {
        assertThatCode(() -> ContradictionStartupGate.verify(true, true)).doesNotThrowAnyException();
    }

    @Test
    void allowsContradictionDisabledRegardlessOfQueen() {
        assertThatCode(() -> ContradictionStartupGate.verify(false, false)).doesNotThrowAnyException();
        assertThatCode(() -> ContradictionStartupGate.verify(false, true)).doesNotThrowAnyException();
    }

    @Test
    void runDelegatesLivePropertiesIntoVerify() {
        ContradictionProperties contradictionProps = new ContradictionProperties();
        contradictionProps.setEnabled(true);
        QueenProperties queenProps = new QueenProperties();
        queenProps.setEnabled(false);

        ContradictionStartupGate gate = new ContradictionStartupGate(contradictionProps, queenProps);

        assertThatThrownBy(() -> gate.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hivemem.queen.enabled");
    }
}
