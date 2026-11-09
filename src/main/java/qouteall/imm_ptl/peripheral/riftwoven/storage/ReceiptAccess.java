package qouteall.imm_ptl.peripheral.riftwoven.storage;

/** Last applied journal transaction, saved atomically with the player's inventory in their vanilla .dat. */
public interface ReceiptAccess {
    String RECEIPT_TAG = "riftwoven_transaction";
    String riftwoven$receipt();
    void riftwoven$receipt(String receipt);
}
