package nl.tudelft.trustchain.valuetransfer.ui.contacts

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.mattskala.itemadapter.Item
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.android.synthetic.main.fragment_contacts_vt.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.Contact
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.ui.BaseFragment
import nl.tudelft.trustchain.valuetransfer.util.closeKeyboard
import nl.tudelft.trustchain.valuetransfer.util.onFocusChange
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.peerchat.community.PeerChatCommunity
import nl.tudelft.trustchain.peerchat.db.PeerChatStore
import nl.tudelft.trustchain.peerchat.entity.ChatMessage
import nl.tudelft.trustchain.peerchat.ui.contacts.ContactItem
import nl.tudelft.trustchain.valuetransfer.R
import nl.tudelft.trustchain.valuetransfer.ValueTransferMainActivity
import nl.tudelft.trustchain.valuetransfer.databinding.FragmentContactsVtBinding
import nl.tudelft.trustchain.valuetransfer.dialogs.ContactAddDialog
import nl.tudelft.trustchain.valuetransfer.dialogs.TransferMoneyDialog

class ContactsFragment : BaseFragment(R.layout.fragment_contacts_vt) {

    private val binding by viewBinding(FragmentContactsVtBinding::bind)

    private val peerChatStore by lazy {
        PeerChatStore.getInstance(requireContext())
    }

    private val contactStore by lazy {
        ContactStore.getInstance(requireContext())
    }

    private val gatewayStore by lazy {
        GatewayStore.getInstance(requireContext())
    }

    private val transactionRepository by lazy {
        TransactionRepository(getIpv8().getOverlay()!!, gatewayStore)
    }

    private fun getPeerChatCommunity(): PeerChatCommunity {
        return getIpv8().getOverlay()
            ?: throw java.lang.IllegalStateException("PeerChatCommunity is not configured")
    }

    private val chatsAdapter = ItemAdapter()
    private val hiddenChatsAdapter = ItemAdapter()
    private val contactsAdapter = ItemAdapter()

    private val peers = MutableStateFlow<List<Peer>>(listOf())

    private val hiddenChatItems: LiveData<List<Item>> by lazy {
        combine(peerChatStore.getAllMessages(), peers) { messages, peers ->
            createHiddenChatItems(messages, peers)
        }.asLiveData()
    }

    private val chatItems: LiveData<List<Item>> by lazy {
        combine(peerChatStore.getContactsWithLastMessages(), peers) { contacts, peers ->
            createChatItems(contacts
                .filter {
                    it.second?.timestamp != null &&
                        contactStore.getContactFromPublicKey(it.first.publicKey) != null
                }, peers)
        }.asLiveData()
    }

    private val contactItems: LiveData<List<Item>> by lazy {
        combine(contactStore.getContacts(), peers) { contacts, peers ->
            createContactItems(contacts, peers)
        }.asLiveData()
    }

    private var hiddenChatsShown = false
    private var searchFilter = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_contacts_vt, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hiddenChatsAdapter.registerRenderer(
            ChatItemRenderer(
                {
                    val args = Bundle()
                    args.putString(ValueTransferMainActivity.ARG_PUBLIC_KEY, it.publicKey.keyToBin().toHex())
                    args.putString(ValueTransferMainActivity.ARG_NAME, it.name)
                    args.putString(ValueTransferMainActivity.ARG_PARENT, ValueTransferMainActivity.contactsFragmentTag)

//                    val contactChatFragment = ContactChatFragment()
//                    contactChatFragment.arguments = args
//                    (requireActivity() as ValueTransferMainActivity).pushFragment(contactChatFragment, ValueTransferMainActivity.contactChatFragmentTag)
                    (requireActivity() as ValueTransferMainActivity).detailFragment(ValueTransferMainActivity.contactChatFragmentTag, args)
                }, {
                    Log.d("TESTJE", "ON DEPOSIT CLICK")
                }, {
                    Log.d("TESTJE", "ON WITHDRAW CLICK")
                }
            )
        )

        chatsAdapter.registerRenderer(
            ChatItemRenderer(
                {
                    val args = Bundle()
                    args.putString(ValueTransferMainActivity.ARG_PUBLIC_KEY, it.publicKey.keyToBin().toHex())
                    args.putString(ValueTransferMainActivity.ARG_NAME, it.name)
                    args.putString(ValueTransferMainActivity.ARG_PARENT, ValueTransferMainActivity.contactsFragmentTag)

//                    val contactChatFragment = ContactChatFragment()
//                    contactChatFragment.arguments = args
//                    (requireActivity() as ValueTransferMainActivity).pushFragment(contactChatFragment, ValueTransferMainActivity.contactChatFragmentTag)
                    (requireActivity() as ValueTransferMainActivity).detailFragment(ValueTransferMainActivity.contactChatFragmentTag, args)
                }, { contact ->
                    TransferMoneyDialog(contact, false, transactionRepository, getPeerChatCommunity()).show(parentFragmentManager, tag)
                }, { contact ->
                    TransferMoneyDialog(contact, true, transactionRepository, getPeerChatCommunity()).show(parentFragmentManager, tag)
                }
            )
        )

        contactsAdapter.registerRenderer(
            ContactsItemRenderer {
                val args = Bundle()
                args.putString(ValueTransferMainActivity.ARG_PUBLIC_KEY, it.publicKey.keyToBin().toHex())
                args.putString(ValueTransferMainActivity.ARG_NAME, it.name)
                args.putString(ValueTransferMainActivity.ARG_PARENT, ValueTransferMainActivity.contactsFragmentTag)

//                val contactChatFragment = ContactChatFragment()
//                contactChatFragment.arguments = args
//                (requireActivity() as ValueTransferMainActivity).pushFragment(contactChatFragment, ValueTransferMainActivity.contactChatFragmentTag)
                (requireActivity() as ValueTransferMainActivity).detailFragment(ValueTransferMainActivity.contactChatFragmentTag, args)
            }
        )

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                peers.value = getPeerChatCommunity().getPeers()
                delay(1000L)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as ValueTransferMainActivity).setActionBarTitle("Contacts")
        (requireActivity() as ValueTransferMainActivity).toggleActionBar(false)
        (requireActivity() as ValueTransferMainActivity).toggleBottomNavigation(true)

        observeHiddenChats(viewLifecycleOwner, hiddenChatsAdapter, hiddenChatItems)
        observeChats(viewLifecycleOwner, chatsAdapter, chatItems)
        observeContacts(viewLifecycleOwner, contactsAdapter)

        binding.tvNoChats.isVisible = chatsAdapter.itemCount == 0 && hiddenChatsShown
        binding.clTogglePendingChats.isVisible = hiddenChatsAdapter.itemCount > 0
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onResume()

        binding.ivSearchBarCancelIcon.setOnClickListener {
            etSearchContact.text = null
            etSearchContact.clearFocus()
            ivSearchBarCancelIcon.isVisible = false
            closeKeyboard(requireContext(), etSearchContact)
        }

        binding.etSearchContact.doAfterTextChanged { searchText ->
            ivSearchBarCancelIcon.isVisible = searchText != null && searchText.isNotEmpty()
            searchFilter = searchText.toString()
            observeContacts(viewLifecycleOwner, contactsAdapter)
        }

        onFocusChange(binding.etSearchContact, requireContext())

        binding.ivAddContactButton.setOnClickListener {
            ContactAddDialog(getIpv8().myPeer.publicKey).show(parentFragmentManager, tag)
        }

        binding.rvHiddenChats.adapter = hiddenChatsAdapter
        binding.rvHiddenChats.layoutManager = LinearLayoutManager(context)

        binding.rvChats.adapter = chatsAdapter
        binding.rvChats.layoutManager = LinearLayoutManager(context)

        binding.rvContacts.adapter = contactsAdapter
        binding.rvContacts.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        observeHiddenChats(viewLifecycleOwner, hiddenChatsAdapter, hiddenChatItems)
        observeChats(viewLifecycleOwner, chatsAdapter, chatItems)
        observeContacts(viewLifecycleOwner, contactsAdapter)

        binding.clTogglePendingChats.setOnClickListener {
            hiddenChatsShown = !hiddenChatsShown

            binding.ivShowHiddenChats.isVisible = hiddenChatsShown
            binding.tvShowHiddenChats.isVisible = hiddenChatsShown
            binding.ivHideHiddenChats.isVisible = !hiddenChatsShown
            binding.tvHideHiddenChats.isVisible = !hiddenChatsShown
            binding.rvHiddenChats.isVisible = !hiddenChatsShown

            binding.tvNoChats.isVisible = chatsAdapter.itemCount == 0 && hiddenChatsShown
        }
    }

    private fun observeHiddenChats(owner: LifecycleOwner, adapter: ItemAdapter, items: LiveData<List<Item>>) {
        items.observe(
            owner,
            Observer { list ->
                if(list.isEmpty()) {
                    binding.rvHiddenChats.visibility = View.GONE
                    hiddenChatsShown = false
                }

                binding.tvNoChats.isVisible = chatsAdapter.itemCount == 0 && hiddenChatsShown
                binding.clTogglePendingChats.isVisible = list.isNotEmpty()
                adapter.updateItems(list)
            }
        )
    }

    private fun observeChats(owner: LifecycleOwner, adapter: ItemAdapter, items: LiveData<List<Item>>) {
        items.observe(
            owner,
            Observer { list ->
                if(list.isEmpty()) {
                    binding.tvNoChats.visibility = View.VISIBLE
                }
                adapter.updateItems(list)
            }
        )
    }

    private fun observeContacts(owner: LifecycleOwner, adapter: ItemAdapter) {
        contactItems.observe(
            owner,
            Observer {
                val list = it.filter { item ->
                    (item as ContactItem).contact.name.contains(searchFilter, ignoreCase = true)
                        || item.contact.mid.contains(searchFilter, ignoreCase = true)
                        || item.contact.publicKey.keyToBin().toHex().contains(searchFilter, ignoreCase = true)
                }
                adapter.updateItems(list)

                binding.tvNoContacts.isVisible = list.isEmpty()
            }
        )
    }

    private fun createContactItems(
        contacts: List<Contact>,
        peers: List<Peer>
    ): List<Item> {
        return contacts.filter {
                it.publicKey != getIpv8().myPeer.publicKey
            }
            .sortedBy { contact ->
                contact.name
            }
            .mapIndexed { _, contact ->
                val peer = peers.find { it.mid == contact.mid }
                ContactItem(
                    contact,
                    null,
                    peer != null && !peer.address.isEmpty(),
                    peer?.bluetoothAddress != null
                )
            }
    }

    private fun createHiddenChatItems(
        messages: List<ChatMessage>,
        peers: List<Peer>
    ): List<ContactItem> {
        return messages
            .filter {
                val publicKey = if(it.outgoing) it.recipient else it.sender
                contactStore.getContactFromPublicKey(publicKey) == null
            }
            .sortedByDescending {
                it.timestamp.time
            }
            .distinctBy {
                if(it.outgoing) it.recipient else it.sender
            }
            .map {
                val publicKey = if(it.outgoing) it.recipient else it.sender
                val peer = peers.find { it.publicKey == publicKey }

                ContactItem(
                    Contact("Unknown contact", publicKey),
                    it,
                    isOnline = peer != null && !peer.address.isEmpty(),
                    isBluetooth = peer?.bluetoothAddress != null
                )
            }
    }

    private fun createChatItems(
        contacts: List<Pair<Contact, ChatMessage?>>,
        peers: List<Peer>
    ): List<Item> {
        return contacts.filter {
                it.first.publicKey != getIpv8().myPeer.publicKey
            }
            .sortedByDescending { item ->
                item.second?.timestamp?.time
            }
            .map { contactWithMessage ->
                val (contact, message) = contactWithMessage
                val peer = peers.find { it.mid == contact.mid }
                ContactItem(
                    contact,
                    message,
                    peer != null && !peer.address.isEmpty(),
                    peer?.bluetoothAddress != null
                )
            }
    }
}
