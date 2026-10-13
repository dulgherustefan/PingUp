package ro.safetyplease.ui.text

import ro.safetyplease.core.data.Conversations
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.msg_friend_added
import ro.safetyplease.ui.resources.msg_group_invite
import kotlin.test.Test
import kotlin.test.assertEquals

class LabelsTest {
    @Test
    fun systemNoticeInAFriendChatIsTheIntroNotTheGroupInvite() {
        assertEquals(Res.string.msg_friend_added, Labels.systemNotice(Conversations.friend(7L)))
        assertEquals(Res.string.msg_group_invite, Labels.systemNotice(Conversations.group(7L)))
    }
}
