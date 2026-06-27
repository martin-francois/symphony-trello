package ch.fmartin.symphony.trello.tracker;

record TrelloCardReference(String lookupId, String url) {
    String key() {
        return lookupId;
    }
}
