package net.corda.finance.contracts.asset

import net.corda.core.contracts.Amount
import net.corda.core.contracts.CommandAndState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.CommandWithParties
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractClassName
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.Issued
import net.corda.core.contracts.MoveCommand
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.contracts.select
import net.corda.core.contracts.verifyMoveCommand
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.internal.Emoji
import net.corda.core.internal.VisibleForTesting
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.seconds
import net.corda.finance.contracts.NetCommand
import net.corda.finance.contracts.NetType
import net.corda.finance.contracts.NettableState
import net.corda.finance.contracts.asset.Obligation.Lifecycle.NORMAL
import net.corda.finance.contracts.utils.sumFungibleOrNull
import net.corda.finance.contracts.utils.sumObligations
import net.corda.finance.contracts.utils.sumObligationsOrNull
import net.corda.finance.contracts.utils.sumObligationsOrZero
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

/**
 * Common interface for the state subsets used when determining nettability of two or more states. Exposes the
 * underlying issued thing.
 */
interface NetState<P : Any> {
    val template: Obligation.Terms<P>
}

/**
 * Subset of state, containing the elements which must match for two obligation transactions to be nettable.
 * If two obligation state objects produce equal bilateral net states, they are considered safe to net directly.
 * Bilateral states are used in close-out netting.
 */
data class BilateralNetState<P : Any>(
        val partyKeys: Set<AbstractParty>,
        override val template: Obligation.Terms<P>
) : NetState<P>

/**
 * Subset of state, containing the elements which must match for two or more obligation transactions to be candidates
 * for netting (this does not include the checks to enforce that everyone's amounts received are the same at the end,
 * which is handled under the verify() function).
 * In comparison to [BilateralNetState], this doesn't include the parties' keys, as ensuring balances match on
 * input and output is handled elsewhere.
 * Used in cases where all parties (or their proxies) are signing, such as central clearing.
 */
data class MultilateralNetState<P : Any>(
        override val template: Obligation.Terms<P>
) : NetState<P>

/**
 * An obligation contract commits the obligor to delivering a specified amount of a fungible asset (for example the
 * [Cash] contract) at a specified future point in time. Settlement transactions may split and merge contracts across
 * multiple input and output states. The goal of this design is to handle amounts owed, and these contracts are expected
 * to be netted/merged, with settlement only for any remainder amount.
 *
 * @param P the product the obligation is for payment of.
 */
class Obligation<P : Any> : Contract {
    companion object {
        const val PROGRAM_ID: ContractClassName = "net.corda.finance.contracts.asset.Obligation"
    }

    /**
     * Represents where in its lifecycle a contract state is, which in turn controls the commands that can be applied
     * to the state. Most states will not leave the [NORMAL] lifecycle. Note that settled (as an end lifecycle) is
     * represented by absence of the state on transaction output.
     */
    @CordaSerializable
    enum class Lifecycle {
        /** Default lifecycle state for a contract, in which it can be settled normally */
        NORMAL,
        /**
         * Indicates the contract has not been settled by its due date. Once in the defaulted state,
         * it can only be reverted to [NORMAL] state by the beneficiary.
         */
        DEFAULTED
    }

    /**
     * Subset of state, containing the elements specified when issuing a new settlement contract.
     *
     * @param P the product the obligation is for payment of.
     * @param acceptableContracts is the contract types that can be accepted, such as cash.
     * @param acceptableIssuedProducts is the assets which are acceptable forms of payment (i.e. GBP issued by the Bank
     * of England).
     * @param dueBefore when payment is due by.
     * @param timeTolerance tolerance value on [dueBefore], to handle clock skew between distributed systems. Generally
     * this would be about 30 seconds.
     */
    @CordaSerializable
    data class Terms<P : Any>(
            /** The hash of the asset contract we're willing to accept in payment for this debt. */
            val acceptableContracts: NonEmptySet<SecureHash>,
            /** The parties whose assets we are willing to accept in payment for this debt. */
            val acceptableIssuedProducts: NonEmptySet<Issued<P>>,

            /** When the contract must be settled by. */
            val dueBefore: Instant,
            val timeTolerance: Duration = 30.seconds
    ) {
        val product: P
            get() = acceptableIssuedProducts.map { it.product }.toSet().single()
    }

    /**
     * A state representing the obligation of one party (obligor) to deliver a specified number of
     * units of an underlying asset (described as token.acceptableIssuedProducts) to the beneficiary
     * no later than the specified time.
     *
     * @param P the product the obligation is for payment of.
     */
    data class State<P : Any>(
            var lifecycle: Lifecycle = Lifecycle.NORMAL,
            /** Where the debt originates from (obligor) */
            val obligor: AbstractParty,
            val template: Terms<P>,
            val quantity: Long,
            /** The public key of the entity the contract pays to */
            val beneficiary: AbstractParty
    ) : FungibleAsset<Terms<P>>, NettableState<State<P>, MultilateralNetState<P>> {
        override val amount: Amount<Issued<Terms<P>>> = Amount(quantity, Issued(obligor.ref(0), template))
        override val exitKeys: Collection<PublicKey> = setOf(beneficiary.owningKey)
        val dueBefore: Instant = template.dueBefore
        override val participants: List<AbstractParty> = listOf(obligor, beneficiary)
        override val owner: AbstractParty = beneficiary

        override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Terms<P>>>, newOwner: AbstractParty): State<P>
                = copy(quantity = newAmount.quantity, beneficiary = newOwner)

        override fun toString() = when (lifecycle) {
            Lifecycle.NORMAL -> "${Emoji.bagOfCash}Debt($amount due $dueBefore to $beneficiary)"
            Lifecycle.DEFAULTED -> "${Emoji.bagOfCash}Debt($amount unpaid by $dueBefore to $beneficiary)"
        }

        override val bilateralNetState: BilateralNetState<P>
            get() {
                check(lifecycle == Lifecycle.NORMAL)
                return BilateralNetState(setOf(obligor, beneficiary), template)
            }
        override val multilateralNetState: MultilateralNetState<P>
            get() {
                check(lifecycle == Lifecycle.NORMAL)
                return MultilateralNetState(template)
            }

        override fun net(other: State<P>): State<P> {
            val netA = bilateralNetState
            val netB = other.bilateralNetState
            require(netA == netB) { "net substates of the two state objects must be identical" }

            return if (obligor.owningKey == other.obligor.owningKey) {
                // Both sides are from the same obligor to beneficiary
                copy(quantity = quantity + other.quantity)
            } else {
                // Issuer and beneficiary are backwards
                copy(quantity = quantity - other.quantity)
            }
        }

        override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Commands.Move(), copy(beneficiary = newOwner))
    }

    // Just for grouping
    @CordaSerializable
    interface Commands : CommandData {
        /**
         * Net two or more obligation states together in a close-out netting style. Limited to bilateral netting
         * as only the beneficiary (not the obligor) needs to sign.
         */
        data class Net(override val type: NetType) : NetCommand

        /**
         * A command stating that a debt has been moved, optionally to fulfil another contract.
         *
         * @param contract the contract this move is for the attention of. Only that contract's verify function
         * should take the moved states into account when considering whether it is valid. Typically this will be
         * null.
         */
        data class Move(override val contract: Class<out Contract>? = null) : MoveCommand

        /**
         * Allows new obligation states to be issued into existence.
         */
        class Issue : TypeOnlyCommandData()

        /**
         * A command stating that the obligor is settling some or all of the amount owed by transferring a suitable
         * state object to the beneficiary. If this reduces the balance to zero, the state object is destroyed.
         *
         * @see MoveCommand
         */
        data class Settle<P : Any>(val amount: Amount<Issued<Terms<P>>>) : CommandData

        /**
         * A command stating that the beneficiary is moving the contract into the defaulted state as it has not been settled
         * by the due date, or resetting a defaulted contract back to the issued state.
         */
        data class SetLifecycle(val lifecycle: Lifecycle) : CommandData {
            val inverse: Lifecycle
                get() = when (lifecycle) {
                    Lifecycle.NORMAL -> Lifecycle.DEFAULTED
                    Lifecycle.DEFAULTED -> Lifecycle.NORMAL
                }
        }

        /**
         * A command stating that the debt is being released by the beneficiary. Normally would indicate
         * either settlement outside of the ledger, or that the obligor is unable to pay.
         */
        data class Exit<P : Any>(val amount: Amount<Issued<Terms<P>>>) : CommandData
    }

    override fun verify(tx: LedgerTransaction) {
        val netCommand = tx.commands.select<Commands.Net>().firstOrNull()
        if (netCommand != null) {
            verifyLifecycleCommand(tx.inputStates, tx.outputStates)
            verifyNetCommand(tx, netCommand)
        } else {
            val groups = tx.groupStates { it: Obligation.State<P> -> it.amount.token }
            for ((inputs, outputs, key) in groups) {
                requireThat {
                    "there are no zero sized outputs" using (outputs.none { it.amount.quantity == 0L })
                }
                val setLifecycleCommand = tx.commands.select<Commands.SetLifecycle>().firstOrNull()
                if (setLifecycleCommand != null) {
                    verifySetLifecycleCommand(inputs, outputs, tx, setLifecycleCommand)
                } else {
                    verifyLifecycleCommand(inputs, outputs)
                    val settleCommand = tx.commands.select<Commands.Settle<P>>().firstOrNull()
                    if (settleCommand != null) {
                        verifySettleCommand(tx, inputs, outputs, settleCommand, key)
                    } else {
                        val issueCommand = tx.commands.select<Commands.Issue>().firstOrNull()
                        if (issueCommand != null) {
                            verifyIssueCommand(tx, inputs, outputs, issueCommand, key)
                        } else {
                            conserveAmount(tx, inputs, outputs, key)
                        }
                    }
                }
            }
        }
    }

    private fun conserveAmount(tx: LedgerTransaction,
                               inputs: List<FungibleAsset<Terms<P>>>,
                               outputs: List<FungibleAsset<Terms<P>>>,
                               key: Issued<Terms<P>>) {
        val issuer = key.issuer
        val terms = key.product
        val inputAmount = inputs.sumObligationsOrNull<P>() ?: throw IllegalArgumentException("there is at least one obligation input for this group")
        val outputAmount = outputs.sumObligationsOrZero(Issued(issuer, terms))

        // If we want to remove obligations from the ledger, that must be signed for by the issuer.
        // A mis-signed or duplicated exit command will just be ignored here and result in the exit amount being zero.
        val exitKeys: Set<PublicKey> = inputs.flatMap { it.exitKeys }.toSet()
        val exitCommand = tx.commands.select<Commands.Exit<P>>(parties = null, signers = exitKeys).singleOrNull { it.value.amount.token == key }
        val amountExitingLedger = exitCommand?.value?.amount ?: Amount(0, Issued(issuer, terms))

        requireThat {
            "there are no zero sized inputs" using (inputs.none { it.amount.quantity == 0L })
            "for reference ${issuer.reference} at issuer ${issuer.party.nameOrNull()} the amounts balance" using
                    (inputAmount == outputAmount + amountExitingLedger)
        }

        verifyMoveCommand<Commands.Move>(inputs, tx.commands)
    }

    private fun verifyIssueCommand(tx: LedgerTransaction,
                                   inputs: List<FungibleAsset<Terms<P>>>,
                                   outputs: List<FungibleAsset<Terms<P>>>,
                                   issueCommand: CommandWithParties<Commands.Issue>,
                                   key: Issued<Terms<P>>) {
        // If we have an issue command, perform special processing: the group is allowed to have no inputs,
        // and the output states must have a deposit reference owned by the signer.
        //
        // Whilst the transaction *may* have no inputs, it can have them, and in this case the outputs must
        // sum to more than the inputs. An issuance of zero size is not allowed.
        //
        // Note that this means literally anyone with access to the network can issue cash claims of arbitrary
        // amounts! It is up to the recipient to decide if the backing party is trustworthy or not, via some
        // as-yet-unwritten identity service. See ADP-22 for discussion.

        // The grouping ensures that all outputs have the same deposit reference and currency.
        val issuer = key.issuer
        val terms = key.product
        val inputAmount = inputs.sumObligationsOrZero(Issued(issuer, terms))
        val outputAmount = outputs.sumObligations<P>()
        val issueCommands = tx.commands.select<Commands.Issue>()
        requireThat {
            "output states are issued by a command signer" using (issuer.party.owningKey in issueCommand.signers)
            "output values sum to more than the inputs" using (outputAmount > inputAmount)
            "there is only a single issue command" using (issueCommands.count() == 1)
        }
    }

    private fun verifySettleCommand(tx: LedgerTransaction,
                                    inputs: List<FungibleAsset<Terms<P>>>,
                                    outputs: List<FungibleAsset<Terms<P>>>,
                                    command: CommandWithParties<Commands.Settle<P>>,
                                    groupingKey: Issued<Terms<P>>) {
        val obligor = groupingKey.issuer.party
        val template = groupingKey.product
        val inputAmount: Amount<Issued<Terms<P>>> = inputs.sumObligationsOrNull() ?: throw IllegalArgumentException("there is at least one obligation input for this group")
        val outputAmount: Amount<Issued<Terms<P>>> = outputs.sumObligationsOrZero(groupingKey)

        // Sum up all asset state objects that are moving and fulfil our requirements

        // The fungible asset contract verification handles ensuring there's inputs enough to cover the output states,
        // we only care about counting how much is output in this transaction. We then calculate the difference in
        // settlement amounts between the transaction inputs and outputs, and the two must match. No elimination is
        // done of amounts paid in by each beneficiary, as it's presumed the beneficiaries have enough sense to do that
        // themselves. Therefore if someone actually signed the following transaction (using cash just for an example):
        //
        // Inputs:
        //  ??1m cash owned by B
        //  ??1m owed from A to B
        // Outputs:
        //  ??1m cash owned by B
        // Commands:
        //  Settle (signed by A)
        //  Move (signed by B)
        //
        // That would pass this check. Ensuring they do not is best addressed in the transaction generation stage.
        val assetStates = tx.outputsOfType<FungibleAsset<*>>()
        val acceptableContract = tx.attachments.any { it.id in template.acceptableContracts }
        requireThat {
            "an acceptable contract is attached" using acceptableContract
        }
        val acceptableAssetStates = assetStates.filter {
            // Restrict the states to those of the correct issuance definition (this normally
            // covers issued product and obligor, but is opaque to us)
            it.amount.token in template.acceptableIssuedProducts
        }
        // Catch that there's nothing useful here, so we can dump out a useful error
        requireThat {
            "there are fungible asset state outputs" using (assetStates.isNotEmpty())
            "there are defined acceptable fungible asset states" using (acceptableAssetStates.isNotEmpty())
        }

        val amountReceivedByOwner = acceptableAssetStates.groupBy { it.owner }
        // Note we really do want to search all commands, because we want move commands of other contracts, not just
        // this one.
        val moveCommands = tx.commands.select<MoveCommand>()
        var totalPenniesSettled = 0L
        val requiredSigners = inputs.map { it.amount.token.issuer.party.owningKey }.toSet()

        for ((beneficiary, obligations) in inputs.groupBy { it.owner }) {
            val settled = amountReceivedByOwner[beneficiary]?.sumFungibleOrNull<P>()
            if (settled != null) {
                val debt = obligations.sumObligationsOrZero(groupingKey)
                require(settled.quantity <= debt.quantity) { "Payment of $settled must not exceed debt $debt" }
                totalPenniesSettled += settled.quantity
            }
        }

        val totalAmountSettled = Amount(totalPenniesSettled, command.value.amount.token)
        requireThat {
            // Insist that we can be the only contract consuming inputs, to ensure no other contract can think it's being
            // settled as well
            "all move commands relate to this contract" using (moveCommands.map { it.value.contract }
                    .all { it == null || it == this@Obligation.javaClass })
            // Settle commands exclude all other commands, so we don't need to check for contracts moving at the same
            // time.
            "amounts paid must match recipients to settle" using inputs.map { it.owner }.containsAll(amountReceivedByOwner.keys)
            "amount in settle command ${command.value.amount} matches settled total $totalAmountSettled" using (command.value.amount == totalAmountSettled)
            "signatures are present from all obligors" using command.signers.containsAll(requiredSigners)
            "there are no zero sized inputs" using inputs.none { it.amount.quantity == 0L }
            "at obligor $obligor the obligations after settlement balance" using
                    (inputAmount == outputAmount + Amount(totalPenniesSettled, groupingKey))
        }
    }

    private fun verifyLifecycleCommand(inputs: List<ContractState>, outputs: List<ContractState>) {
        val filteredInputs = inputs.filterIsInstance<State<P>>()
        val filteredOutputs = outputs.filterIsInstance<State<P>>()
        requireThat {
            "all inputs are in the normal state " using filteredInputs.all { it.lifecycle == Lifecycle.NORMAL }
            "all outputs are in the normal state " using filteredOutputs.all { it.lifecycle == Lifecycle.NORMAL }
        }
    }

    private fun verifyNetCommand(tx: LedgerTransaction, command: CommandWithParties<NetCommand>) {
        val groups = when (command.value.type) {
            NetType.CLOSE_OUT -> tx.groupStates { it: Obligation.State<P> -> it.bilateralNetState }
            NetType.PAYMENT -> tx.groupStates { it: Obligation.State<P> -> it.multilateralNetState }
        }
        for ((groupInputs, groupOutputs, key) in groups) {

            val template = key.template
            // Create two maps of balances from obligors to beneficiaries, one for input states, the other for output states.
            val inputBalances = extractAmountsDue(template, groupInputs)
            val outputBalances = extractAmountsDue(template, groupOutputs)

            // Sum the columns of the matrices. This will yield the net amount payable to/from each party to/from all other participants.
            // The two summaries must match, reflecting that the amounts owed match on both input and output.
            requireThat {
                "all input states use the same template" using (groupInputs.all { it.template == template })
                "all output states use the same template" using (groupOutputs.all { it.template == template })
                "amounts owed on input and output must match" using (sumAmountsDue(inputBalances) == sumAmountsDue
                (outputBalances))
            }

            // TODO: Handle proxies nominated by parties, i.e. a central clearing service
            val involvedParties: Set<PublicKey> = groupInputs.map { it.beneficiary.owningKey }.union(groupInputs.map { it.obligor.owningKey }).toSet()
            when (command.value.type) {
            // For close-out netting, allow any involved party to sign
                NetType.CLOSE_OUT -> require(command.signers.intersect(involvedParties).isNotEmpty()) { "any involved party has signed" }
            // Require signatures from all parties (this constraint can be changed for other contracts, and is used as a
            // placeholder while exact requirements are established), or fail the transaction.
                NetType.PAYMENT -> require(command.signers.containsAll(involvedParties)) { "all involved parties have signed" }
            }
        }
    }

    /**
     * A default command mutates inputs and produces identical outputs, except that the lifecycle changes.
     */
    @VisibleForTesting
    private fun verifySetLifecycleCommand(inputs: List<FungibleAsset<Terms<P>>>,
                                          outputs: List<FungibleAsset<Terms<P>>>,
                                          tx: LedgerTransaction,
                                          setLifecycleCommand: CommandWithParties<Commands.SetLifecycle>) {
        // Default must not change anything except lifecycle, so number of inputs and outputs must match
        // exactly.
        require(inputs.size == outputs.size) { "Number of inputs and outputs must match" }

        // If we have an default command, perform special processing: issued contracts can only be defaulted
        // after the due date, and default/reset can only be done by the beneficiary
        val expectedInputLifecycle = setLifecycleCommand.value.inverse
        val expectedOutputLifecycle = setLifecycleCommand.value.lifecycle

        // Check that we're past the deadline for ALL involved inputs, and that the output states correspond 1:1
        for ((stateIdx, input) in inputs.withIndex()) {
            if (input is State<P>) {
                val actualOutput = outputs[stateIdx]
                val deadline = input.dueBefore
                val timeWindow = tx.timeWindow
                val expectedOutput = input.copy(lifecycle = expectedOutputLifecycle)

                requireThat {
                    "there is a time-window from the authority" using (timeWindow != null)
                    "the due date has passed" using (timeWindow!!.fromTime?.isAfter(deadline) == true)
                    "input state lifecycle is correct" using (input.lifecycle == expectedInputLifecycle)
                    "output state corresponds exactly to input state, with lifecycle changed" using (expectedOutput == actualOutput)
                }
            }
        }
        val owningPubKeys = inputs.filter { it is State<P> }.map { (it as State<P>).beneficiary.owningKey }.toSet()
        val keysThatSigned = setLifecycleCommand.signers.toSet()
        requireThat {
            "the owning keys are a subset of the signing keys" using keysThatSigned.containsAll(owningPubKeys)
        }
    }
}


/**
 * Convert a list of settlement states into total from each obligor to a beneficiary.
 *
 * @return a map of obligor/beneficiary pairs to the balance due.
 */
fun <P : Any> extractAmountsDue(product: Obligation.Terms<P>, states: Iterable<Obligation.State<P>>): Map<Pair<AbstractParty, AbstractParty>, Amount<Obligation.Terms<P>>> {
    val balances = HashMap<Pair<AbstractParty, AbstractParty>, Amount<Obligation.Terms<P>>>()

    states.forEach { state ->
        val key = Pair(state.obligor, state.beneficiary)
        val balance = balances[key] ?: Amount(0L, product)
        balances[key] = balance + Amount(state.amount.quantity, state.amount.token.product)
    }

    return balances
}

/**
 * Net off the amounts due between parties.
 */
fun <P : AbstractParty, T : Any> netAmountsDue(balances: Map<Pair<P, P>, Amount<T>>): Map<Pair<P, P>, Amount<T>> {
    val nettedBalances = HashMap<Pair<P, P>, Amount<T>>()

    balances.forEach { balance ->
        val (obligor, beneficiary) = balance.key
        val oppositeKey = Pair(beneficiary, obligor)
        val opposite = (balances[oppositeKey] ?: Amount(0L, balance.value.token))
        // Drop zero balances
        if (balance.value > opposite) {
            nettedBalances[balance.key] = (balance.value - opposite)
        } else if (opposite > balance.value) {
            nettedBalances[oppositeKey] = (opposite - balance.value)
        }
    }

    return nettedBalances
}

/**
 * Calculate the total balance movement for each party in the transaction, based off a summary of balances between
 * each obligor and beneficiary.
 *
 * @param balances payments due, indexed by obligor and beneficiary. Zero balances are stripped from the map before being
 * returned.
 * @param P type of party to operate on.
 * @param T token that balances represent
 */
fun <P : AbstractParty, T : Any> sumAmountsDue(balances: Map<Pair<P, P>, Amount<T>>): Map<P, Long> {
    val sum = HashMap<P, Long>()

    // Fill the map with zeroes initially
    balances.keys.forEach {
        sum[it.first] = 0L
        sum[it.second] = 0L
    }

    for ((key, amount) in balances) {
        val (obligor, beneficiary) = key
        // Subtract it from the obligor
        sum[obligor] = sum[obligor]!! - amount.quantity
        // Add it to the beneficiary
        sum[beneficiary] = sum[beneficiary]!! + amount.quantity
    }

    // Strip zero balances
    val iterator = sum.iterator()
    while (iterator.hasNext()) {
        val amount = iterator.next().value
        if (amount == 0L) {
            iterator.remove()
        }
    }

    return sum
}

infix fun <T : Any> Obligation.State<T>.at(dueBefore: Instant) = copy(template = template.copy(dueBefore = dueBefore))
infix fun <T : Any> Obligation.State<T>.between(parties: Pair<AbstractParty, AbstractParty>) = copy(obligor = parties.first, beneficiary = parties.second)
infix fun <T : Any> Obligation.State<T>.`owned by`(owner: AbstractParty) = copy(beneficiary = owner)
infix fun <T : Any> Obligation.State<T>.`issued by`(party: AbstractParty) = copy(obligor = party)
// For Java users:
@Suppress("unused")
fun <T : Any> Obligation.State<T>.ownedBy(owner: AbstractParty) = copy(beneficiary = owner)

@Suppress("unused")
fun <T : Any> Obligation.State<T>.issuedBy(party: AnonymousParty) = copy(obligor = party)
