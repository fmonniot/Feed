# User Experience improvements


## Android App

List of things to improve while testing the android app

- Remove the filter chips on the article lists. 
- Needs to have pull to refresh on the inbox zero page
- way too much top padding on the top bar. 
- also too much padding in the nav bar (article list disappear roughly 10dp above it)
- login screen flashing on app load => doesn't look good, need to do something about it
- Remove all screen transition. We can decide later on what they should actually be. For now they are mostly a distraction
- feeds screen: the add feed button should probably not be at the end of the list. Maybe something similar to the web version and put it in the app bar?
- settings > import opml > choose => doesn't do anything

## Web app

- article list: the list item could be slighter larger in width.
- article view: Reduce padding a lot. Currently use only half the available width.
- identity language different: remove the box in settings/subscriptions. Screenshot useful here.
- Synced just now ago in bottom left, "just now" isn't a duration
- login page hasn't been redesigned yet

## Others

Things that aren't necessarily just UX items

- Rework TODO.md. It's too much unnecessary context now.
  And how I do project management in general. Not sure how though.
  Maybe multiple task lists ? Want to keep history of reasons, but not overwhelm myself
  when I pause work for a month and has to go back to it.
    - Maybe feature, bug, improvement, deferred?
    - Or by importance: blocker, must have, nice to have, backlog?
- Make a better call on what's necessary for me to use the app on a daily basis
  and what is nice to have (e.g. retention settings or refresh interval)
- Reconsider the /logs endpoint in the app. There has to be an easier way to do observability.
- Find a way to give claude access to screenshot of the app running and of the design reference so that it can better understand the difference. Create final slate of work to update design to be pixel perfect (or as close as it makes sense to)
