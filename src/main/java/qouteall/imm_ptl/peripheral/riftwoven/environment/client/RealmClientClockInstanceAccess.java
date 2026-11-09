package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

/** Cold client accessor for the otherwise immutable public clock-instance API. */
public interface RealmClientClockInstanceAccess {
    void riftwoven$set(long ticks, float fraction, float rate);
}
