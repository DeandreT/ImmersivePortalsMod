package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;

/** Traits belong to an extracted sky state, not to whichever dimension the player occupies. */
public interface RealmSkyStateAccess {
    CelestialLayout riftwoven$layout();
    void riftwoven$layout(CelestialLayout layout);
}
