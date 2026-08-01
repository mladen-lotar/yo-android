package hr.theshop.yo.ui.main

import hr.theshop.yo.domain.model.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * LazyColumn keys must be unique. Compose treats a duplicate as fatal, not as a warning.
 *
 * The band list was keyed on the LABEL, and two groups may share a name - nothing prevents it,
 * and `GroupRepositoryImplTest` has a test asserting that creating a duplicate name yields a
 * distinct group, so it is a deliberate contract. The result was a crash on every launch of the
 * home screen. Groups are local and survive logout, so the only escapes were Clear Data,
 * reinstall, or account deletion - whose UI sits behind the screen that is crash-looping.
 */
class SendTargetKeyTest {

    @Test
    fun `two groups sharing a name still have distinct keys`() {
        val first = SendTarget.YoGroup(Group(name = "FAM", memberUsernames = listOf("ADA")))
        val second = SendTarget.YoGroup(Group(name = "FAM", memberUsernames = listOf("LEO")))

        assertEquals("the labels are deliberately allowed to collide", first.label, second.label)
        assertNotEquals("the keys must not", first.key, second.key)
    }

    @Test
    fun `a friend and a group sharing a name have distinct keys`() {
        val friend = SendTarget.Friend("FAM")
        val group = SendTarget.YoGroup(Group(name = "FAM", memberUsernames = emptyList()))

        assertEquals(friend.label, group.label)
        assertNotEquals(friend.key, group.key)
    }

    @Test
    fun `a group's key is stable across reads of the same group`() {
        val group = Group(name = "FAM", memberUsernames = listOf("ADA"))

        assertEquals(SendTarget.YoGroup(group).key, SendTarget.YoGroup(group).key)
    }

    @Test
    fun `the whole band list is unique even when every label is the same`() {
        val targets: List<SendTarget> =
            listOf(SendTarget.Friend("FAM")) +
                (1..5).map { SendTarget.YoGroup(Group(name = "FAM", memberUsernames = emptyList())) }

        assertEquals(targets.size, targets.map { it.key }.toSet().size)
    }
}
