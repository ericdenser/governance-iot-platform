package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"time"
)

var secretKey = os.Getenv("INFRA_API_KEY")

func apiKeyAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		clientKey := r.Header.Get("X-API-Key")

		if clientKey != secretKey {
			fmt.Println("Tentativa de acesso bloqueada (API Key inválida): " + clientKey)
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		next(w, r)
	}
}

func main() {
	secretKey = os.Getenv("INFRA_API_KEY")

	if secretKey == "" {
		log.Fatal("INFRA_API_KEY não definida")
	}

	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/reload-crl", apiKeyAuth(reloadCRLHandler))
	http.HandleFunc("/restart-broker", apiKeyAuth(restartBrokerHandler))

	fmt.Println("Infra Executor rodando na porta 8089")

	srv := &http.Server{
		Addr:    ":8089",
		Handler: nil,
		// WriteTimeout alto: docker restart pode demorar (SIGTERM grace + start).
		// govApi espera essa resposta pra dar 200 no revoke — nao pode timeoutar antes.
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	log.Fatal(srv.ListenAndServe())
}
func healthHandler(w http.ResponseWriter, r *http.Request) {
	fmt.Println(w, "OK")
}

func reloadCRLHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	fmt.Println("Executando reload da CRL...")

	brokerContainer := os.Getenv("BROKER_CONTAINER_NAME")
	if brokerContainer == "" {
		brokerContainer = "iot-broker"
	}
	cmd := exec.Command("docker", "kill", "-s", "HUP", brokerContainer)
	output, err := cmd.CombinedOutput()

	if err != nil {
		fmt.Println("Erro:", err)
		fmt.Println("output:", string(output))
		http.Error(w, string(output), http.StatusInternalServerError)
		return
	}

	fmt.Println("✔ sucesso:", string(output))
	fmt.Fprintln(w, "CRL reloaded")
}

// restartBrokerHandler mata todas as conexoes TLS ativas via `docker restart`.
// Usado pelo revoke — CRL sozinho nao derruba socket TLS ja estabelecido, entao
// devices always-on continuariam publicando ate desconectar espontaneamente.
// Restart + CRL garante que o revogado seja bloqueado no proximo handshake.
func restartBrokerHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	brokerContainer := os.Getenv("BROKER_CONTAINER_NAME")
	if brokerContainer == "" {
		brokerContainer = "iot-broker"
	}

	fmt.Println("Executando restart do broker...")
	// -t 2: aceita SIGTERM por 2s, depois SIGKILL. Mosquitto nao tem shutdown
	// hook critico — 2s e suficiente pra flush de logs.
	cmd := exec.Command("docker", "restart", "-t", "2", brokerContainer)
	output, err := cmd.CombinedOutput()

	if err != nil {
		fmt.Println("Erro:", err)
		fmt.Println("output:", string(output))
		http.Error(w, string(output), http.StatusInternalServerError)
		return
	}

	fmt.Println("✔ sucesso:", string(output))
	fmt.Fprintln(w, "Broker restarted")
}
