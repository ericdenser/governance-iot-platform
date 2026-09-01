use crate::messaging::messaging::messaging_server::MessagingServer;
use crate::messaging::messaging::{Message, MessageSentResponse, RegisterRequest};
use ::log::{debug, error};
use tonic::{Request, Response, Status};
use crate::persistence::persistence::{ack_message, get_messages, queue_message};
use std::future::Future;
use std::sync::{Arc};
use tempfile::NamedTempFile;
use tokio::net::{UnixListener, UnixStream};
use tokio_stream::wrappers::UnixListenerStream;
use tonic::transport::{Channel, Endpoint, Server, Uri};
use tower::service_fn;
use crate::log::logs::init_logs;
use crate::messaging::messaging::messaging_client::MessagingClient;
use tokio::sync::Mutex;
use tokio_rusqlite::Connection;
use crate::MessagingController;


static INITIALISED: Mutex<bool> = Mutex::const_new(false);

async fn server_and_client_stub() -> (impl Future<Output = ()>, MessagingClient<Channel>) {
    let socket = NamedTempFile::new().unwrap();
    let socket = Arc::new(socket.into_temp_path());
    std::fs::remove_file(&*socket).unwrap();

    let uds = UnixListener::bind(&*socket).unwrap();
    let stream = UnixListenerStream::new(uds);

    let serve_future = async {
        let result = Server::builder()
            .add_service(MessagingServer::new(MessagingController {}))
            .serve_with_incoming(stream)
            .await;
        // Server must be running fine...
        assert!(result.is_ok());
    };

    let socket = Arc::clone(&socket);
    // Connect to the server over a Unix socket
    // The URL will be ignored.
    let channel = Endpoint::try_from("http://any.url")
        .unwrap()
        .connect_with_connector(service_fn(move |_: Uri| {
            let socket = Arc::clone(&socket);
            async move { UnixStream::connect(&*socket).await }
        }))
        .await
        .unwrap();

    let client = MessagingClient::new(channel);

    (serve_future, client)
}

async fn setup() -> tokio_rusqlite::Result<()>{
    let mut initialised = INITIALISED.lock().await;
    if *initialised {
        return Ok(());
    }

    init_logs();

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

    *initialised = true;

    return Ok(());
}

#[tokio::test]
async fn receive_message() {
    match setup().await {
        Ok(_) => { debug!("Tests setup")}
        Err(e) => { panic!("Error to setup tests {:?}", e)}
    }

    let (serve_future, mut client) = server_and_client_stub().await;

    let request_future = async {
        let message = Message{
            topic: "topic_test".to_string(),
            from: "from_test".to_string(),
            message: "test_message".to_string(),
        };

        debug!("Sending message: {:?}", message);

        let response = client
            .send_message(Request::new(message.clone()))
            .await
            .unwrap()
            .into_inner();

        debug!("Response: {:?}", response);

        assert_eq!(response.status,"success".to_string());

        let mut stream = client
            .subscribe(Request::new(RegisterRequest{
                topic: "topic_test".to_string(),
            }))
            .await
            .unwrap()
            .into_inner();

        let Some(message_received) = stream.message().await.unwrap() else { panic!("Message not received") };
        debug!("Message received: {:?}", message_received);

        assert_eq!(message, message_received);
    };

    // Wait for completion, when the client request future completes
    tokio::select! {
        _ = serve_future => panic!("server returned first"),
        _ = request_future => (),
    }
}

#[tokio::test]
async fn send_message_test() {
    match setup().await {
        Ok(_) => { debug!("Tests setup")}
        Err(e) => { panic!("Error to setup tests {:?}", e)}
    }

    let (serve_future, mut client) = server_and_client_stub().await;

    let request_future = async {
        let message = Message{
            topic: "topic_test".to_string(),
            from: "from_test".to_string(),
            message: "test_message".to_string(),
        };

        debug!("Sending message: {:?}", message);

        let response = client
            .send_message(Request::new(message))
            .await
            .unwrap()
            .into_inner();

        debug!("Response: {:?}", response);

        assert_eq!(response.status,"success".to_string());
    };

    // Wait for completion, when the client request future completes
    tokio::select! {
        _ = serve_future => panic!("server returned first"),
        _ = request_future => (),
    }
}
