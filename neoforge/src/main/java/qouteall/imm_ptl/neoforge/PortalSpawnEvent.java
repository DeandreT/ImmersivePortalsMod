package qouteall.imm_ptl.neoforge;

import net.neoforged.bus.api.Event;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;

/** Fired after a custom portal generation has created its portal entities. */
public final class PortalSpawnEvent extends Event {
    private final PortalGenInfo info;

    public PortalSpawnEvent(PortalGenInfo info) { this.info = info; }
    public PortalGenInfo getInfo() { return info; }
}
