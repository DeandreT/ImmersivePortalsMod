package qouteall.imm_ptl.peripheral.riftwoven.environment;

/** Server-only state attached to each realm level. No client types belong in this contract. */
public interface RealmEnvironmentAccess {
    RealmEnvironmentState riftwoven$environment();
}
