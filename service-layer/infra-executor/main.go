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

	fmt.Println("Infra Executor rodando na porta 8089")

	srv := &http.Server{
		Addr:         ":8089",
		Handler:      nil,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 10 * time.Second,
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

	cmd := exec.Command("docker", "kill", "-s", "HUP", "iot_broker")
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
