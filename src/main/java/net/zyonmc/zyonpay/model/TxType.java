package net.zyonmc.zyonpay.model;

/** Origin of a transaction. Names match the ENUM of the {@code transactions} table. */
public enum TxType {
    PAY,
    ADMIN,
    VAULT,
    API
}
