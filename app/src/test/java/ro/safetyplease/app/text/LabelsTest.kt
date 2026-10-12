package ro.safetyplease.app.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.app.R
import ro.safetyplease.core.data.Conversations

class LabelsTest {
    @Test
    fun systemNoticeInAFriendChatIsTheIntroNotTheGroupInvite() {
        assertEquals(R.string.msg_friend_added, Labels.systemNotice(Conversations.friend(7L)))
        assertEquals(R.string.msg_group_invite, Labels.systemNotice(Conversations.group(7L)))
    }
}
