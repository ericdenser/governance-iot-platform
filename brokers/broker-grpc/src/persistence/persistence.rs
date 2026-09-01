use chrono::{DateTime, Utc};
use tokio_rusqlite::{Connection, Result};
use crate::messaging::messaging::Message;

#[derive(Clone, Debug)]
pub struct MessageQuery {
    id: i32,
    topic: String,
    owner: String,
    message: String,
    date: DateTime<Utc>,
    date_ack: DateTime<Utc>,
    ack: bool
}

impl MessageQuery {
    pub fn new(owner: String, topic: String, message: String) -> MessageQuery {
        MessageQuery {
            id: 0,
            topic,
            owner,
            message,
            date: Utc::now(),
            date_ack: Utc::now(),
            ack: false,
        }
    }

    pub fn get_id(self) -> i32 { return self.id; }
    pub fn get_topic(self) -> String { return self.topic; }
    pub fn get_owner(self) -> String { return self.owner; }
    pub fn get_message(self) -> String { return self.message; }
}

pub async fn init_database() -> Result<()> {
    let conn = Connection::open("message_queue.db").await?;

    conn.call(|conn| {
        let query = conn.execute(
            "CREATE TABLE IF NOT EXISTS message_queue  (
            id    INTEGER PRIMARY KEY,
            topic  TEXT NOT NULL,
            owner TEXT,
            message TEXT,
            date TIMESTAMP,
            date_ack TIMESTAMP,
            ack BIT NOT NULL
            )",
            [], // empty list of parameters.
        )?;

        Ok(query)
    }).await?;

    conn.close().await.expect("Error to close connection");

    Ok(())
}
pub async fn queue_message(message: Message) -> Result<()> {
    let conn = Connection::open("message_queue.db").await?;

    let message_query = MessageQuery::new(message.from, message.topic, message.message);

    conn.call(move |conn | {
        let query = conn.execute(
            "INSERT INTO message_queue (owner, topic, message, date, date_ack, ack) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            (message_query.owner, message_query.topic, message_query.message, message_query.date, message_query.date_ack, message_query.ack)
        )?;

        Ok(query)
    }).await?;

    conn.close().await.expect("Error to close connection");

    Ok(())
}

pub async fn get_messages(topic: String) -> Result<Vec<MessageQuery>> {
    let conn = Connection::open("message_queue.db").await?;

    let messages = conn.call(move |conn| {
        let query = format!("SELECT * from message_queue WHERE ack = false AND topic = '{}'", topic);

        let mut stmt = conn.prepare(query.as_str())?;
        let messages_iter = stmt.query_map((), |row| {
            Ok(MessageQuery {
                id: row.get(0)?,
                topic: row.get(1)?,
                owner: row.get(2)?,
                message: row.get(3)?,
                date: row.get(4)?,
                date_ack: row.get(5)?,
                ack: row.get(6)?,
            })
        })?;

        let mut messages: Vec<MessageQuery> = Vec::new();

        for message in messages_iter {
            messages.push(message.unwrap());
        }


        Ok(messages)
    }).await?;

    conn.close().await.expect("Error to close connection");

    Ok(messages)

}

pub async fn ack_message(id: i32) -> Result<()> {
    let conn = Connection::open("message_queue.db").await?;

    conn.call(move |conn| {
        let query = conn.execute(
            format!("UPDATE message_queue SET ack = 1 WHERE id = {};", id).as_str(), ()
        )?;

        Ok(query)
    }).await?;

    conn.close().await.expect("Error to close connection");

    Ok(())
}
