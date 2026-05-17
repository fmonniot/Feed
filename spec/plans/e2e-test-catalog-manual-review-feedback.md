Context: .claude/plans/new-design-rollout-progress-log.md and .claude/plans/new-design-rollout-implementation-plan.md

This is the feedback following the manual verification phase. Overall it's a good first step, but we will need to iterate on a few things. This message is grouped in two sections:

1. direct feedback to the end-to-end test catalog laid out in new-design-rollout-implementation-plan.md
2. How I want to process going forward

The first section usefulness is twofolds: provide context to the second section, as well as context to create tickets to fix bugs reported in it.



# End-to-end test catalog review

It follows the groups and IDs defined in the plan. Within in each group it use the following format:

- ID: <status>; <notes>

where status is
- "ok" if all work
- "ok web, fail android" if it failed on android
- "fail" if it fails on both platforms

and notes is some unstructered text about the task (explanation for the failure, improvement to the flow, etc…).


## Authentication & session

- AUTH-1: ok; on web needs to validate auth when typing the enter key at the end (keyboard shortcut), on android should change the keyboard type so that enter goes to the password field (and not a new line). Similary, should offer to login with the keyboard when on the keyboard.

- AUTH-2: ok
- AUTH-3: ok
- AUTH-4: ok

Reloading the page always present the login page, needs to remember that we were already logged in.


## Feed list & navigation

all fail on android; no articles are shown in the list and so not possible to test anything

- FEED-1: ok web; On Android there are no feeds being shown
- FEED-2: ok web; Needs to rename the spec item, "field notes" is a special case of the design. In reality this is the subscription name. URL path in spec is wrong as well, but it works
- FEED-3: Starring/Favorite isn't something we will support; the design has it because it was too generic. We can remove this spec item.
- FEED-4: ok web
- FEED-5: ok web


## Reader

all fail on android; no articles are shown in the list and so not possible to test anything

- READ-1: ok web; need to change the spec here. We want the url route to change to reflect the content of the read panel. Unless I'm misinterpreting the spec item, it's quite vague
- READ-2: ok web
- READ-3: Starring/Favorite isn't something we will support
- READ-4: ok web
- READ-5: fail web; settings doesn't have a default font size entry
- READ-6: fail web; the url is present, but it's not a hyperlink
- READ-7: ok web

## Subscriptions

- SUBS-1: ok
- SUBS-2: ok
- SUBS-3: ok
- SUBS-4: ok; on web, the sub drop down containing the rename button is constrained by the subs-feed-list div. It should render on top of it instead. The edit field should be prepopulated with the sub name (it currently is empty)
- SUBS-5: not fully tested because instruction unclear. Both sub icon had the same hue for different name
- SUBS-6: ok

## Settings (and prefs persistence)

- SET-1: fail web, ok android; settings not present in the web version, cannot read article on android so can't validate usage but the value is persisted
- SET-2: ok
- SET-3: can't test yet
- SET-4: web fail, android unknown; setting not present on web, can't test android due to article list bug
- SET-5: don't have a small opml file to test with
- SET-6: web fail, android ok; should it even be an option on the web? Production code will always be same host and in dev we can hardcode to localhost:3000

## Mobile-specific

Can't test until we resolve the article not showing up in the list.


## Error / edge

- ERR-1: web ok, android fail; no way to refresh on android. Need a pull-to-refresh mechanism on any article list screens.
- ERR-2: not yet tested
- ERR-3: fail; neither apps prompted for a login after having received 401 responses from the server



# Going forward

Ok, we are at a point where we need more rigor in documenting/specing what it is I want this project to actually do. The end to end test catalog is a good first step and was really useful so let's reuse this format. We do want to account for the few web/mobile difference in UI though.

A series of notes to take into account when working on that new spec:

- Needs to be more precise as for what settings are present in web/mobile, and what each of them is supposed to do
- Explicit document things we are not going to support (e.g. star/favorite). Ask me if you aren't sure if a feature should be supported or not.





In term of action item for you in this session:

1. Create tickets in TODO.md to fix the bugs reported in the end-to-end catalog review section
2. Create a functional spec of scenarios in spec/FEATURES.md