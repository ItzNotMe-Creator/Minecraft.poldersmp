CREATE TABLE nether_state(id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, document TEXT NOT NULL);
CREATE TABLE nether_history(operation TEXT PRIMARY KEY, requested INTEGER NOT NULL, completed INTEGER, outcome TEXT NOT NULL, backup TEXT, detail TEXT);
