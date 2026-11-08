package qouteall.imm_ptl.core.ducks;

public interface IEServerPlayNetworkHandler {
    public boolean ip_hasAwaitingTeleport();

    /** Send and await a dimension-aware correction without changing player physics. */
    void ip_sendPositionCorrection();
}
