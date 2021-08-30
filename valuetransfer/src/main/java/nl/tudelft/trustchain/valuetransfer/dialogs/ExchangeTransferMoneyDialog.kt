package nl.tudelft.trustchain.valuetransfer.dialogs

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.android.synthetic.main.dialog_exchange_transfer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.Contact
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.peerchat.community.PeerChatCommunity
import nl.tudelft.trustchain.valuetransfer.R
import nl.tudelft.trustchain.valuetransfer.ValueTransferMainActivity
import nl.tudelft.trustchain.valuetransfer.db.IdentityStore
import nl.tudelft.trustchain.valuetransfer.ui.contacts.ContactChatFragment
import nl.tudelft.trustchain.valuetransfer.ui.contacts.ContactsFragment
import nl.tudelft.trustchain.valuetransfer.ui.exchange.ExchangeFragment
import nl.tudelft.trustchain.valuetransfer.ui.walletoverview.WalletOverviewFragment
import nl.tudelft.trustchain.valuetransfer.util.*


class ExchangeTransferMoneyDialog(
    private val recipient: Contact?,
    private val amount: String?,
    private val isTransfer: Boolean,
    private val message: String? = ""
) : DialogFragment() {

    private lateinit var parentActivity: ValueTransferMainActivity
    private lateinit var trustChainCommunity: TrustChainCommunity
    private lateinit var peerChatCommunity: PeerChatCommunity
    private lateinit var contactStore: ContactStore
    private lateinit var transactionRepository: TransactionRepository

    private var selectedContact: Contact? = recipient
    private var transactionAmount = 0L
    private var transactionMessage = message ?: ""

    private lateinit var contactAdapter: ArrayAdapter<Contact>

    private lateinit var transferButton: Button
    private lateinit var requestMoneyContactButton: Button
    private lateinit var requestMoneyQRButton: Button
    private lateinit var messageView: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): BottomSheetDialog {
        return activity?.let {
            val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.BaseBottomSheetDialog)
            val view = layoutInflater.inflate(R.layout.dialog_exchange_transfer, null)

            parentActivity = requireActivity() as ValueTransferMainActivity
            trustChainCommunity = parentActivity.getCommunity(ValueTransferMainActivity.trustChainCommunityTag) as TrustChainCommunity
            peerChatCommunity = parentActivity.getCommunity(ValueTransferMainActivity.peerChatCommunityTag) as PeerChatCommunity
            contactStore = parentActivity.getStore(ValueTransferMainActivity.contactStoreTag) as ContactStore
            transactionRepository = parentActivity.getStore(ValueTransferMainActivity.transactionRepositoryTag) as TransactionRepository

            val contactSpinner = view.findViewById<Spinner>(R.id.spinnerContact)
            val selectedContactView = view.findViewById<TextView>(R.id.tvSelectedContact)
            val transactionAmountView = view.findViewById<EditText>(R.id.etTransactionAmount)
            messageView = view.findViewById(R.id.etTransactionMessage)
            messageView.setText(transactionMessage)

            val recipientTitleView = view.findViewById<TextView>(R.id.tvRecipientTitle)
            val addNewContactView = view.findViewById<ConstraintLayout>(R.id.clAddNewContact)
            val addNewContactSwitch = view.findViewById<Switch>(R.id.switchAddNewContact)
            val loadingSpinner = view.findViewById<ProgressBar>(R.id.pbLoadingSpinner)

            transferButton = view.findViewById(R.id.btnTransferMoney)
            toggleButton(transferButton, selectedContact != null && transactionAmount != 0L)

            requestMoneyContactButton = view.findViewById(R.id.btnRequestMoneyContact)
            requestMoneyQRButton = view.findViewById(R.id.btnRequestMoneyQR)

            transactionAmountView.addDecimalLimiter()

            if(amount != null) {
                transactionAmount = amount.toLong()
                transactionAmountView.setText(formatAmount(amount).toString())
            }

            contactSpinner.isVisible = selectedContact == null
            selectedContactView.isVisible = selectedContact != null

            requestMoneyContactButton.isVisible = !isTransfer && recipient != null
            requestMoneyQRButton.isVisible = !isTransfer && recipient == null
            transferButton.isVisible = isTransfer
            view.findViewById<TextView>(R.id.tvTitleTransfer).isVisible = isTransfer
            view.findViewById<TextView>(R.id.tvTitleRequest).isVisible = !isTransfer

            lifecycleScope.launchWhenCreated {
                while(isActive) {
                    transactionAmountView.hint = "Balance: ${(requireActivity() as ValueTransferMainActivity).getBalance(true)}"
                    delay(1000)
                }
            }

            // Select contact from dropdown, or nobody in case of an unspecified request
            if(recipient == null) {
                lifecycleScope.launch(Dispatchers.Main) {
                    var contacts: MutableList<Contact> = contactStore.getContacts()
                        .first()
                        .filter {
                            it.publicKey != trustChainCommunity.myPeer.publicKey
                        }
                        .sortedBy {
                            it.name
                        }
                        .toMutableList()

                    // Add option to select nobody for money request (qrcode only)
                    if(!isTransfer) {
                        contacts.add(
                            0,
                            Contact(
                                "No contact selected",
                                trustChainCommunity.myPeer.publicKey
                            )
                        )
                    }

                    if(!isTransfer || (isTransfer && contacts.isNotEmpty())) {
                        selectedContactView.isVisible = false

                        contactAdapter = object : ArrayAdapter<Contact>(
                            requireContext(),
                            android.R.layout.simple_spinner_item,
                            contacts
                        ) {
                            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val textView: TextView = super.getDropDownView(position, convertView, parent) as TextView
                                val params = textView.layoutParams
                                params.height = resources.getDimensionPixelSize(R.dimen.textViewHeight)
                                textView.layoutParams = params
                                textView.gravity = Gravity.CENTER_VERTICAL

                                textView.text = contacts[position].name

                                // Create a request for an unspecified contact/person
                                if (!isTransfer && position == 0) {
                                    textView.setTextColor(
                                        ContextCompat.getColor(
                                            requireContext(),
                                            R.color.dark_gray
                                        )
                                    )
                                    textView.setTypeface(null, Typeface.ITALIC)
                                }

                                // Currently selected item background in dropdown
                                if (position == contactSpinner.selectedItemPosition) {
                                    textView.background = ColorDrawable(Color.LTGRAY)
                                }

                                return textView
                            }

                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val textView: TextView = super.getView(position, convertView, parent) as TextView
                                textView.text = contacts[position].name

                                // No contact selected option for money request
                                if (!isTransfer && position == 0) {
                                    textView.setTextColor(
                                        ContextCompat.getColor(
                                            requireContext(),
                                            R.color.dark_gray
                                        )
                                    )
                                    textView.setTypeface(null, Typeface.ITALIC)
                                }

                                return textView
                            }
                        }
                        contactSpinner.adapter = contactAdapter

                        // Select item in spinner in case the contact is pre-assigned
                        if (selectedContact != null) {
                            val selected = contacts.indexOf(selectedContact)
                            contactSpinner.setSelection(selected)
                        }
                    }else{
                        selectedContactView.isVisible = true
                        contactSpinner.isVisible = false
                    }
                }

                contactSpinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(p0: AdapterView<*>?) {
                            toggleButton(transferButton, false)
                        }

                        // Get selected contact for transfer and request
                        override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                            selectedContact = if (!isTransfer && position == 0) {
                                null
                            } else {
                                contactSpinner.selectedItem as Contact
                            }

                            toggleAllButtons()
                        }
                    }

            // Display the recipients name instead of a spinner when the contact is pre-assigned
            }else{
                recipientTitleView.text = "Recipient"

                // Check whether the contact is already in my contacts
                if(contactStore.getContactFromPublicKey(selectedContact!!.publicKey) == null) {
                    selectedContactView.text = "${recipient.name} (not a contact)"
                    addNewContactView.isVisible = true
                }else{
                    selectedContactView.text = contactStore.getContactFromPublicKey(selectedContact!!.publicKey)!!.name
                }

                toggleAllButtons()
            }

            onFocusChange(transactionAmountView, requireContext())
            onFocusChange(messageView, requireContext())

            transactionAmountView.doAfterTextChanged {
                transactionAmount = formatAmount(transactionAmountView.text.toString())

                toggleAllButtons()
            }

            messageView.doAfterTextChanged {
                transactionMessage = messageView.text.toString()
            }

            transferButton.setOnClickListener {
                loadingSpinner.isVisible = true
                transferButton.isVisible = false
                try {
                    // Add contact if it is isn't in your contacts and
                    if(selectedContact != null && contactStore.getContactFromPublicKey(selectedContact!!.publicKey) == null) {
                        if(addNewContactSwitch.isChecked) {
                            contactStore.addContact(selectedContact!!.publicKey, selectedContact!!.name)
                        }
                    }

                    // Create proposal block to the recipient
                    val block = transactionRepository.sendTransferProposalSync(selectedContact!!.publicKey.keyToBin(), transactionAmount)
                    if (block == null) {
                        parentActivity.displaySnackbar(requireContext(), "Insufficient balance", view = view.rootView, type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR, extraPadding = true)
//                        parentActivity.displaySnackbar(fragment.requireView(), fragment.requireContext(), "Insufficient balance", ValueTransferMainActivity.SNACKBAR_TYPE_ERROR)
//                        Toast.makeText(requireContext(), "Insufficient balance", Toast.LENGTH_LONG).show()
                    } else {
                        peerChatCommunity.sendMessageWithTransaction(transactionMessage, block.calculateHash(), selectedContact!!.publicKey)
                        parentActivity.displaySnackbar(requireContext(), "Transfer of ${transactionAmountView.text} ET to ${selectedContact!!.name}", isShort = false)
//                        parentActivity.displaySnackbar(parentActivity.getRootView().findViewById(R.id.container), requireContext(), "Transfer of ${transactionAmountView.text} ET to ${selectedContact!!.name}", isShort = false)
//                        parentActivity.displaySnackbar(fragment.requireView(), fragment.requireContext(), "Transfer of ${transactionAmountView.text} ET to ${selectedContact!!.name}", isShort = false)
//                        Toast.makeText(requireContext(), "Transfer of ${transactionAmountView.text} to ${selectedContact!!.name}", Toast.LENGTH_LONG).show()
                        bottomSheetDialog.dismiss()
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                    parentActivity.displaySnackbar(requireContext(), "Unexpected error occurred", view = view.rootView, type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR, extraPadding = true)
//                    parentActivity.displaySnackbar(fragment.requireView(), fragment.requireContext(), "Unexpected error occurred", ValueTransferMainActivity.SNACKBAR_TYPE_ERROR)
//                    Toast.makeText(requireContext(), "Unexpected error occurred", Toast.LENGTH_SHORT).show()
                }
                loadingSpinner.isVisible = false
                transferButton.isVisible = true
            }

            requestMoneyContactButton.setOnClickListener {
                loadingSpinner.isVisible = true
                requestMoneyContactButton.isVisible = false
                peerChatCommunity.sendTransferRequest(transactionMessage, transactionAmount, selectedContact!!.publicKey)

                parentActivity.displaySnackbar(requireContext(), "Transfer request of ${transactionAmountView.text} ET sent to ${selectedContact!!.name}", isShort = false)
                bottomSheetDialog.dismiss()
            }

            requestMoneyQRButton.setOnClickListener {
                loadingSpinner.isVisible = true
                requestMoneyQRButton.isVisible = false

                closeKeyboard(requireContext(), view)
                val map = mapOf(
                    "public_key" to trustChainCommunity.myPeer.publicKey.keyToBin().toHex(),
                    "amount" to transactionAmount.toString(),
                    "name" to IdentityStore.getInstance(requireContext()).getIdentity()!!.content.givenNames,
                    "type" to "transfer"
                )

                bottomSheetDialog.dismiss()
                QRCodeDialog("REQUEST MONEY", "Have your friend scan this QR-code", mapToJSON(map).toString()).show(parentFragmentManager,tag)
            }

            bottomSheetDialog.setContentView(view)
            bottomSheetDialog.show()

            bottomSheetDialog
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun toggleAllButtons() {
        val nonEmptyContact = selectedContact != null
        val nonEmptyAmount = transactionAmount != 0L

        transferButton.isVisible = isTransfer

        if(isTransfer) {
            toggleButton(transferButton, nonEmptyContact && nonEmptyAmount)
        }else{
            // Situation in which money is requested from one person
            requestMoneyContactButton.isVisible = nonEmptyContact
            toggleButton(requestMoneyContactButton, nonEmptyContact && nonEmptyAmount)
            messageView.isVisible = nonEmptyContact

            // Situation in which money is requested to anyone using QR code only
            requestMoneyQRButton.isVisible = !nonEmptyContact
            toggleButton(requestMoneyQRButton, !nonEmptyContact && nonEmptyAmount)
        }
    }
}
