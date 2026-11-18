package shiori

import java.time.Instant

/**
 * The trade side of a bookshop: suppliers, purchase orders, returns to suppliers and customer orders.
 * Like everything else, these are rebuilt from the journal.
 */

/** A publisher or distributor the shop buys from. */
data class Supplier(
    val id: String,
    val name: String,
    /** Usual trade discount on the publisher's price, in basis points (3 500 = 35 %). */
    val discountBp: Int,
    /** Whether unsold copies may go back (France: retours; Japan: 返品 on consignment; Taiwan: 退書). */
    val returnable: Boolean,
    /** How long after the last delivery a return is accepted; 0 = no limit agreed. */
    val returnDays: Int,
    /** How to reach the supplier: an account number, a website, a phone line. */
    val contact: String,
) {
    /** What one copy usually costs from this supplier, tax included, rounded down. */
    fun cost(price: Long): Long = price - maxDiscount(price, discountBp)
}

data class PoLine(val isbn: String, val qty: Long, val cost: Long, var received: Long = 0) {
    val remaining: Long get() = maxOf(qty - received, 0)
}

/** A purchase order sent to one supplier. The customer orders it serves are listed so the shop knows who is waiting. */
data class PurchaseOrder(
    val id: String,
    val at: Instant,
    val supplier: String,
    val lines: List<PoLine>,
    val orders: List<String>,
    var cancelled: Instant? = null,
) {
    val open: Boolean get() = cancelled == null && lines.any { it.remaining > 0 }
}

data class ReturnLine(val isbn: String, val qty: Long, val cost: Long)

/** Copies sent back to a supplier, who owes the shop a credit for them. */
data class SupplierReturn(val id: String, val at: Instant, val supplier: String, val lines: List<ReturnLine>, val reason: String) {
    val credit: Long get() = lines.sumOf { it.qty * it.cost }
}

enum class OrderState { WAITING, ORDERED, READY, COLLECTED, CANCELLED }

/**
 * A customer asked for a book. The journal knows only a customer number: the name and the phone number
 * live in a separate file that the shop can erase (see Customers), because the journal can never forget.
 */
data class CustomerOrder(
    val id: String,
    val at: Instant,
    val customer: String,
    val isbn: String,
    val qty: Long,
    var notified: Instant? = null,
    var collected: Instant? = null,
    var sale: String? = null,
    var cancelled: Instant? = null,
) {
    val closed: Boolean get() = collected != null || cancelled != null
}

/** Copies of one book received from one supplier: what a return to that supplier may not exceed. */
data class Lot(var received: Long = 0, var returned: Long = 0, var lastIn: Instant? = null, var lastCost: Long = 0) {
    val returnable: Long get() = maxOf(received - returned, 0)
}
