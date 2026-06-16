mod config;
mod event;
mod log;
mod messaging;
mod persistence;
mod test;

use crate::config::config::Config;
use crate::log::logs::init_logs;
use crate::messaging::messaging::messaging_server::{Messaging, MessagingServer};
use crate::messaging::messaging::{Message, MessageSentResponse, RegisterRequest};
use ::log::{debug, error};
use std::fs::File;
use std::io::Read;
use std::time::Duration;
use tokio::sync::mpsc;
use tonic::transport::{Identity, ServerTlsConfig};
use tonic::{transport::Server, Request, Response, Status};
use crate::persistence::persistence::{ack_message, get_messages, init_database, queue_message};

#[derive(Default)]
pub struct MessagingController {}

#[tonic::async_trait]
impl Messaging for MessagingController {
    type SubscribeStream = tokio_stream::wrappers::ReceiverStream<Result<Message, Status>>;
    async fn subscribe(
        &self,
        request: Request<RegisterRequest>,
    ) -> Result<Response<Self::SubscribeStream>, Status> {
        // converting request in stream
        let register = request.into_inner();

        let (tx, rx) = mpsc::channel(4);

        // creating a new task
        tokio::spawn(async move {
            loop {
                let possible_messages = get_messages(register.clone().topic);
                match possible_messages.await {
                    Ok(messages) => {
                        for message in messages.clone() {
                            let unwrap_message = tx
                                .send(Ok(Message {
                                    topic: message.clone().get_topic(),
                                    from: message.clone().get_owner(),
                                    message: message.clone().get_message(),
                                })).await;

                            match unwrap_message {
                                Ok(_) => {
                                    debug!("Message unwrapped {:?}", message);
                                    match ack_message(message.get_id()).await {
                                        Ok(_) => { debug!("Message acknowledged"); }
                                        Err(err) => { error!("Error to acknowledge message: {:?}", err); }
                                    }
                                }
                                Err(err) => { error!("Error to unwrap message: {:?}", err) }
                            }
                        };
                    }
                    Err(err) => { error!("Error to read messages: {:?}", err)}
                }
            }
        });

        Ok(Response::new(rx.into()))
    }

    async fn send_message(
        &self,
        request: Request<Message>,
    ) -> Result<Response<MessageSentResponse>, Status> {
        let message = request.into_inner();

        return match queue_message(message).await {
            Ok(_) => {
                debug!("Message queued with success");
                Ok(Response::new(MessageSentResponse {
                    status: "success".into(),
                }))
            },
            Err(error) => {
                error!("Failed to queue message {:?}", error);
                Ok(Response::new(MessageSentResponse {
                    status: "failed".into(),
                }))
            }
        };
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    init_logs();
    debug!("Logs started");

    let mut file = File::open("config/server.yml")?;
    let mut contents = String::new();
    file.read_to_string(&mut contents)?;

    let config: Config = serde_yaml::from_str(&contents).unwrap();
    debug!("Config file: {:?}", config);

    let messaging = MessagingController::default();

    match init_database().await {
        Ok(_) => { debug!("SQLite database initialized with success ")},
        Err(err) => {
            error!("Error to initialize SQLite database");
            return Err(Box::try_from(err).unwrap())
        }
    }

    let ser = MessagingServer::new(messaging);

    if config.with_tls() {
        debug!("Starting GRPC Server with TLS");
        let cert = std::fs::read_to_string(config.get_cert_file())?;
        let key = std::fs::read_to_string(config.get_key_file())?;

        let identity = Identity::from_pem(cert, key);

        Server::builder()
            .tls_config(ServerTlsConfig::new().identity(identity))?
            .http2_keepalive_interval(Option::from(Duration::from_secs(30)))
            .add_service(ser)
            .serve(config.get_addr().parse().unwrap())
            .await?;
    } else {
        debug!("Starting GRPC Server without TLS");

        Server::builder()
            .http2_keepalive_interval(Option::from(Duration::from_secs(30)))
            .add_service(ser)
            .serve(config.get_addr().parse().unwrap())
            .await?;
    }

    debug!("Server listening on {}", config.get_addr());

    Ok(())
}
