/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import org.signal.core.util.concurrent.subscribeWithSubject
import org.signal.core.util.orNull
import org.signal.paging.ProxyPagingController
import org.thoughtcrime.securesms.components.reminder.Reminder
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ScheduledMessagesRepository
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.v2.data.ConversationElementKey
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Quote
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.database.model.StickerRecord
import org.thoughtcrime.securesms.database.model.StoryViewState
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob
import org.thoughtcrime.securesms.keyboard.KeyboardUtil
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.messagerequests.MessageRequestRepository
import org.thoughtcrime.securesms.messagerequests.MessageRequestState
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.search.MessageResult
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.BubbleUtil
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.hasGiftBadge
import org.thoughtcrime.securesms.util.rx.RxStore
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import org.whispersystems.signalservice.api.push.ServiceId
import java.util.Optional
import kotlin.time.Duration

/**
 * ConversationViewModel, which operates solely off of a thread id that never changes.
 */
class ConversationViewModel(
  val threadId: Long,
  requestedStartingPosition: Int,
  private val repository: ConversationRepository,
  recipientRepository: ConversationRecipientRepository,
  messageRequestRepository: MessageRequestRepository,
  private val scheduledMessagesRepository: ScheduledMessagesRepository
) : ViewModel() {

  private val disposables = CompositeDisposable()

  private val scrollButtonStateStore = RxStore(ConversationScrollButtonState()).addTo(disposables)
  val scrollButtonState: Flowable<ConversationScrollButtonState> = scrollButtonStateStore.stateFlowable
    .distinctUntilChanged()
    .observeOn(AndroidSchedulers.mainThread())
  val showScrollButtonsSnapshot: Boolean
    get() = scrollButtonStateStore.state.showScrollButtons
  val unreadCount: Int
    get() = scrollButtonStateStore.state.unreadCount

  val recipient: Observable<Recipient> = recipientRepository.conversationRecipient

  private val _conversationThreadState: Subject<ConversationThreadState> = BehaviorSubject.create()
  val conversationThreadState: Single<ConversationThreadState> = _conversationThreadState.firstOrError()

  private val _markReadProcessor: PublishProcessor<Long> = PublishProcessor.create()
  val markReadRequests: Flowable<Long> = _markReadProcessor
    .onBackpressureBuffer()
    .distinct()

  val pagingController = ProxyPagingController<ConversationElementKey>()

  val groupMemberServiceIds: Observable<List<ServiceId>> = recipientRepository
    .groupRecord
    .filter { it.isPresent && it.get().isV2Group }
    .map { it.get().requireV2GroupProperties().getMemberServiceIds() }
    .distinctUntilChanged()
    .observeOn(AndroidSchedulers.mainThread())

  @Volatile
  var recipientSnapshot: Recipient? = null
    private set

  val isPushAvailable: Boolean
    get() = recipientSnapshot?.isRegistered == true && Recipient.self().isRegistered

  val wallpaperSnapshot: ChatWallpaper?
    get() = recipientSnapshot?.wallpaper

  private val _inputReadyState: Observable<InputReadyState>
  val inputReadyState: Observable<InputReadyState>

  private val hasMessageRequestStateSubject: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(false)
  val hasMessageRequestState: Boolean
    get() = hasMessageRequestStateSubject.value ?: false

  private val refreshReminder: Subject<Unit> = PublishSubject.create()
  val reminder: Observable<Optional<Reminder>>

  private val refreshIdentityRecords: Subject<Unit> = PublishSubject.create()
  private val identityRecordsStore: RxStore<IdentityRecordsState> = RxStore(IdentityRecordsState())
  val identityRecordsObservable: Observable<IdentityRecordsState> = identityRecordsStore.stateFlowable.toObservable()
  val identityRecordsState: IdentityRecordsState
    get() = identityRecordsStore.state

  private val _searchQuery = BehaviorSubject.createDefault("")
  val searchQuery: Observable<String> = _searchQuery

  val storyRingState = recipient
    .switchMap { StoryViewState.getForRecipientId(it.id) }
    .subscribeOn(Schedulers.io())
    .distinctUntilChanged()
    .observeOn(AndroidSchedulers.mainThread())

  init {
    disposables += recipient
      .subscribeBy {
        recipientSnapshot = it
      }

    disposables += repository.getConversationThreadState(threadId, requestedStartingPosition)
      .subscribeBy(onSuccess = {
        pagingController.set(it.items.controller)
        _conversationThreadState.onNext(it)
      })

    disposables += conversationThreadState.flatMapObservable { threadState ->
      Observable.create<Unit> { emitter ->
        val controller = threadState.items.controller
        val messageUpdateObserver = DatabaseObserver.MessageObserver {
          controller.onDataItemChanged(ConversationElementKey.forMessage(it.id))
        }
        val messageInsertObserver = DatabaseObserver.MessageObserver {
          controller.onDataItemInserted(ConversationElementKey.forMessage(it.id), 0)
        }
        val conversationObserver = DatabaseObserver.Observer {
          controller.onDataInvalidated()
        }

        ApplicationDependencies.getDatabaseObserver().registerMessageUpdateObserver(messageUpdateObserver)
        ApplicationDependencies.getDatabaseObserver().registerMessageInsertObserver(threadId, messageInsertObserver)
        ApplicationDependencies.getDatabaseObserver().registerConversationObserver(threadId, conversationObserver)

        emitter.setCancellable {
          ApplicationDependencies.getDatabaseObserver().unregisterObserver(messageUpdateObserver)
          ApplicationDependencies.getDatabaseObserver().unregisterObserver(messageInsertObserver)
          ApplicationDependencies.getDatabaseObserver().unregisterObserver(conversationObserver)
        }
      }
    }.subscribeOn(Schedulers.io()).subscribe()

    recipientRepository
      .conversationRecipient
      .filter { it.isRegistered }
      .take(1)
      .subscribeBy { RetrieveProfileJob.enqueue(it.id) }
      .addTo(disposables)

    disposables += recipientRepository
      .conversationRecipient
      .skip(1) // We can safely skip the first emission since this is used for updating the header on future changes
      .subscribeBy { pagingController.onDataItemChanged(ConversationElementKey.threadHeader) }

    disposables += scrollButtonStateStore.update(
      repository.getMessageCounts(threadId)
    ) { counts, state ->
      state.copy(
        unreadCount = counts.unread,
        hasMentions = counts.mentions != 0
      )
    }

    _inputReadyState = Observable.combineLatest(
      recipientRepository.conversationRecipient,
      recipientRepository.groupRecord
    ) { recipient, groupRecord ->
      InputReadyState(
        conversationRecipient = recipient,
        messageRequestState = messageRequestRepository.getMessageRequestState(recipient, threadId),
        groupRecord = groupRecord.orNull(),
        isClientExpired = SignalStore.misc().isClientDeprecated,
        isUnauthorized = TextSecurePreferences.isUnauthorizedReceived(ApplicationDependencies.getApplication())
      )
    }.doOnNext {
      hasMessageRequestStateSubject.onNext(it.messageRequestState != MessageRequestState.NONE)
    }
    inputReadyState = _inputReadyState.observeOn(AndroidSchedulers.mainThread())

    recipientRepository.conversationRecipient.map { Unit }.subscribeWithSubject(refreshReminder, disposables)

    reminder = Observable.combineLatest(refreshReminder.startWithItem(Unit), recipientRepository.groupRecord) { _, groupRecord -> groupRecord }
      .subscribeOn(Schedulers.io())
      .flatMapMaybe { groupRecord -> repository.getReminder(groupRecord.orNull()) }
      .observeOn(AndroidSchedulers.mainThread())

    Observable.combineLatest(
      refreshIdentityRecords.startWithItem(Unit).observeOn(Schedulers.io()),
      recipient,
      recipientRepository.groupRecord
    ) { _, r, g -> Pair(r, g) }
      .subscribeOn(Schedulers.io())
      .flatMapSingle { (r, g) -> repository.getIdentityRecords(r, g.orNull()) }
      .subscribeBy { newState ->
        identityRecordsStore.update { newState }
      }
      .addTo(disposables)
  }

  fun setSearchQuery(query: String?) {
    _searchQuery.onNext(query ?: "")
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun setShowScrollButtons(showScrollButtons: Boolean) {
    scrollButtonStateStore.update {
      it.copy(showScrollButtons = showScrollButtons)
    }
  }

  fun getQuotedMessagePosition(quote: Quote): Single<Int> {
    return repository.getQuotedMessagePosition(threadId, quote)
  }

  fun moveToSearchResult(messageResult: MessageResult): Single<Int> {
    return repository.getMessageResultPosition(threadId, messageResult)
  }

  fun getNextMentionPosition(): Single<Int> {
    return repository.getNextMentionPosition(threadId)
  }

  fun moveToMessage(dateReceived: Long, author: RecipientId): Single<Int> {
    return repository.getMessagePosition(threadId, dateReceived, author)
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun moveToMessage(messageRecord: MessageRecord): Single<Int> {
    return repository.getMessagePosition(threadId, messageRecord.dateReceived, messageRecord.fromRecipient.id)
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun setLastScrolled(lastScrolledTimestamp: Long) {
    repository.setLastVisibleMessageTimestamp(
      threadId,
      lastScrolledTimestamp
    )
  }

  fun markGiftBadgeRevealed(messageRecord: MessageRecord) {
    if (messageRecord.isOutgoing && messageRecord.hasGiftBadge()) {
      repository.markGiftBadgeRevealed(messageRecord.id)
    }
  }

  fun muteConversation(until: Long) {
    recipient.firstOrError()
      .subscribeBy {
        repository.setConversationMuted(it.id, until)
      }
      .addTo(disposables)
  }

  fun getContactPhotoIcon(context: Context, glideRequests: GlideRequests): Single<ShortcutInfoCompat> {
    return recipient.firstOrError().flatMap {
      repository.getRecipientContactPhotoBitmap(context, glideRequests, it)
    }
  }

  fun startExpirationTimeout(messageRecord: MessageRecord) {
    repository.startExpirationTimeout(messageRecord)
  }

  fun updateReaction(messageRecord: MessageRecord, emoji: String): Completable {
    val oldRecord = messageRecord.oldReactionRecord()

    return if (oldRecord != null && oldRecord.emoji == emoji) {
      repository.sendReactionRemoval(messageRecord, oldRecord)
    } else {
      repository.sendNewReaction(messageRecord, emoji)
    }
  }

  /**
   * @return Maybe which only emits if the "React with any" sheet should be displayed.
   */
  fun updateCustomReaction(messageRecord: MessageRecord, hasAddedCustomEmoji: Boolean): Maybe<Unit> {
    val oldRecord = messageRecord.oldReactionRecord()

    return if (oldRecord != null && hasAddedCustomEmoji) {
      repository.sendReactionRemoval(messageRecord, oldRecord).toMaybe()
    } else {
      Maybe.just(Unit)
    }
  }

  fun getKeyboardImageDetails(uri: Uri): Maybe<KeyboardUtil.ImageDetails> {
    return repository.getKeyboardImageDetails(uri)
  }

  private fun MessageRecord.oldReactionRecord(): ReactionRecord? {
    return reactions.firstOrNull { it.author == Recipient.self().id }
  }

  fun sendMessage(
    metricId: String?,
    threadRecipient: Recipient,
    body: String,
    slideDeck: SlideDeck?,
    scheduledDate: Long,
    messageToEdit: MessageId?,
    quote: QuoteModel?,
    mentions: List<Mention>,
    bodyRanges: BodyRangeList?,
    contacts: List<Contact>,
    linkPreviews: List<LinkPreview>,
    preUploadResults: List<MessageSender.PreUploadResult>,
    isViewOnce: Boolean
  ): Completable {
    return repository.sendMessage(
      threadId = threadId,
      threadRecipient = threadRecipient,
      metricId = metricId,
      body = body,
      slideDeck = slideDeck,
      scheduledDate = scheduledDate,
      messageToEdit = messageToEdit,
      quote = quote,
      mentions = mentions,
      bodyRanges = bodyRanges,
      contacts = contacts,
      linkPreviews = linkPreviews,
      preUploadResults = preUploadResults,
      isViewOnce = isViewOnce
    ).observeOn(AndroidSchedulers.mainThread())
  }

  fun resetVerifiedStatusToDefault(unverifiedIdentities: List<IdentityRecord>) {
    disposables += repository.resetVerifiedStatusToDefault(unverifiedIdentities)
      .subscribe {
        refreshIdentityRecords.onNext(Unit)
      }
  }

  fun updateIdentityRecordsInBackground() {
    refreshIdentityRecords.onNext(Unit)
  }

  fun updateIdentityRecords(): Completable {
    val state: IdentityRecordsState = identityRecordsStore.state
    if (state.recipient == null) {
      return Completable.error(IllegalStateException("No recipient in records store"))
    }

    return repository.getIdentityRecords(state.recipient, state.group)
      .doOnSuccess { newState ->
        identityRecordsStore.update { newState }
      }
      .flatMapCompletable { Completable.complete() }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun getTemporaryViewOnceUri(mmsMessageRecord: MmsMessageRecord): Maybe<Uri> {
    return repository.getTemporaryViewOnceUri(mmsMessageRecord).observeOn(AndroidSchedulers.mainThread())
  }

  fun canShowAsBubble(context: Context): Observable<Boolean> {
    return recipient
      .map { Build.VERSION.SDK_INT >= ConversationUtil.CONVERSATION_SUPPORT_VERSION && BubbleUtil.canBubble(context, it, threadId) }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun copyToClipboard(context: Context, messageParts: Set<MultiselectPart>): Maybe<CharSequence> {
    return repository.copyToClipboard(context, messageParts)
  }

  fun resendMessage(conversationMessage: ConversationMessage): Completable {
    return repository.resendMessage(conversationMessage.messageRecord)
  }

  fun getRequestReviewState(): Observable<RequestReviewState> {
    return _inputReadyState
      .flatMapSingle { state -> repository.getRequestReviewState(state.conversationRecipient, state.groupRecord, state.messageRequestState) }
      .distinctUntilChanged()
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun getSlideDeckAndBodyForReply(context: Context, conversationMessage: ConversationMessage): Pair<SlideDeck, CharSequence> {
    return repository.getSlideDeckAndBodyForReply(context, conversationMessage)
  }

  fun resolveMessageToEdit(conversationMessage: ConversationMessage): Single<ConversationMessage> {
    return repository.resolveMessageToEdit(conversationMessage)
  }

  fun deleteSlideData(slides: List<Slide>) {
    repository.deleteSlideData(slides)
  }

  fun updateStickerLastUsedTime(stickerRecord: StickerRecord, timestamp: Duration) {
    repository.updateStickerLastUsedTime(stickerRecord, timestamp)
  }

  fun getScheduledMessagesCount(): Observable<Int> {
    return scheduledMessagesRepository
      .getScheduledMessageCount(threadId)
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun markLastSeen() {
    repository.markLastSeen(threadId)
  }
}
