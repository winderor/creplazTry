# Implementation Plan - Item Removal on Play/Next

This plan ensures that items are correctly removed from the playlist and preferences whenever they are played or skipped, regardless of the interaction method (UI buttons, list clicks, or automatic playback). It also addresses the inconsistency between the UI display order and the playback order.

## Proposed Changes

### [Component] UI and Playback Synchronization

#### [MODIFY] [MainActivity.kt](file:///G:/creplaz/app/src/main/java/com/example/myapplication/MainActivity.kt)
- Centralize the sorting logic for articles to ensure the UI and `PlaybackService` always use the same order.
- Update `loadSavedArticles` to sort `articlePlaylist` before updating the adapter and setting it to the service.
- Update `ArticleAdapter`'s item click listener to trigger playback for the selected article. When an article is clicked, it will be moved to the front of the playlist (or played directly) and subsequently removed once finished (or immediately, depending on preference).
- *Decision*: We will stick to removing items after they finish playing to allow for pausing/resuming, but we will ensure the *correct* item is removed by keeping the service in sync with the sorted UI list.

#### [MODIFY] [PlaybackService.kt](file:///G:/creplaz/app/src/main/java/com/example/myapplication/PlaybackService.kt)
- Ensure that `skip()` and `onDone` reliably remove the article from both the in-memory playlist and `SharedPreferences`.
- Add a method to play a specific article by moving it to the top of the playlist if it's already in it.

## Verification Plan

### Automated Tests
- N/A (Manual verification on device is more effective for TTS and UI sync).

### Manual Verification
1. **Launch App**: Observe the list of articles.
2. **Press Play**: Verify the top article in the UI starts playing.
3. **Press Next**: Verify the current article is removed from the list and the next one starts.
4. **Let Article Finish**: Verify the finished article is removed from the list and the next one starts automatically.
5. **Click Article in List**: (Optional improvement) Verify the clicked article starts playing and is removed from its current position (or marked as playing).
6. **Restart App**: Verify removed articles are gone from the persistent storage.
