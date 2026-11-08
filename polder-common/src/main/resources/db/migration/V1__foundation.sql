CREATE TABLE accounts (
    uuid TEXT PRIMARY KEY NOT NULL,
    balance INTEGER NOT NULL CHECK(balance >= 0 AND typeof(balance) = 'integer')
);
CREATE TABLE transfers (
    id TEXT PRIMARY KEY NOT NULL,
    sender TEXT NOT NULL,
    recipient TEXT NOT NULL,
    amount INTEGER NOT NULL CHECK(amount > 0 AND typeof(amount) = 'integer'),
    CHECK(sender <> recipient)
);
