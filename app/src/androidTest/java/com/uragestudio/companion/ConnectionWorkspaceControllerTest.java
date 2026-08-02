package com.uragestudio.companion;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ConnectionWorkspaceControllerTest {
    private SecurePairingStore pairingStore;

    @Before
    public void prepare() {
        Context context = ApplicationProvider.getApplicationContext();
        pairingStore = new SecurePairingStore(context);
        pairingStore.clear();
        new ConnectionRouteStore(context).useLan();
    }

    @After
    public void cleanUp() {
        pairingStore.clear();
    }

    @Test
    public void workspaceRailShowsOwnedWorkspacesWithoutCreateSubmenu() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Connect")).perform(click());
            onView(withText(startsWith("Pair locally for fast transfers"))).check(matches(isDisplayed()));

            onView(withText("Chat")).perform(click());
            onView(withText(startsWith("Continue a compact conversation"))).check(matches(isDisplayed()));

            onView(withText("Image")).perform(click());
            onView(withText(startsWith("Generate an image on the dashboard"))).check(matches(isDisplayed()));

            onView(withText("Gallery")).perform(click());
            onView(withText(startsWith("Preview recent dashboard media"))).check(matches(isDisplayed()));
        }
    }

    @Test
    public void savedPairingRestoresGalleryAsStartupWorkspace() throws Exception {
        pairingStore.save("http://192.168.1.20:4782", "test-device-token-for-ui-restoration", "");

        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withText(startsWith("Preview recent dashboard media"))).check(matches(isDisplayed()));
        }
    }

    @Test
    public void routeSelectionShowsOnlyItsConfigurationSection() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Connect")).perform(click());
            onView(withText("Dashboard pairing")).check(matches(isDisplayed()));
            onView(withText("Matrix relay")).check(matches(not(isDisplayed())));

            onView(withText("LAN")).perform(click());
            onData(allOf(instanceOf(String.class), is("Internet"))).perform(click());

            onView(withText("Matrix relay")).check(matches(isDisplayed()));
            onView(withText("Dashboard pairing")).check(matches(not(isDisplayed())));
        }
    }

    @Test
    public void connectionExposesDashboardThemeFamilies() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Connect")).perform(click());
            onView(withText("Studio theme")).check(matches(isDisplayed()));
            onView(withText("Follow paired dashboard theme")).check(matches(isDisplayed()));
            onView(withText("Apply theme preference")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void navigationAdaptsAtTheTabletWidthBoundary() {
        Configuration phone = new Configuration();
        phone.smallestScreenWidthDp = 599;
        Configuration tablet = new Configuration();
        tablet.smallestScreenWidthDp = 600;

        assertFalse(WorkspaceRailController.usesTabletLayout(phone));
        assertTrue(WorkspaceRailController.usesTabletLayout(tablet));
    }

    @Test
    public void selectedWorkflowSurvivesActivityRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Image")).perform(click());
            scenario.recreate();
            onView(withText(startsWith("Generate an image on the dashboard"))).check(matches(isDisplayed()));
        }
    }
}
