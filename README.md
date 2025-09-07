# 📨 Serviço de Mensagens Distribuídas com Consistência Eventual e Autenticação Básica

Este projeto implementa um sistema de **mensagens distribuídas** em Java, onde múltiplos nós (servidores) se comunicam entre si.  
O objetivo é demonstrar:
- **Replicação assíncrona** de mensagens entre nós.
- **Autenticação básica** de usuários.
- **Consistência eventual**, ou seja, mesmo que um nó fique offline temporariamente, ao retornar ele recupera as mensagens que perdeu (reconciliação).

## ⚙️ Tecnologias utilizadas
- **Java 21**
- **Gson** (para serialização JSON)
- **HttpServer** nativo do Java (`com.sun.net.httpserver`)
- Execução local com múltiplos nós (exemplo: `node1`, `node2`, `node3`)

## ▶️ Como executar

### 1. Clonar ou abrir o projeto
Abra este projeto em sua IDE preferida (ex.: IntelliJ IDEA).

### 2. Executando com IntelliJ IDEA (recomendado)
1. Crie **3 Run Configurations** (Aplicação Java) para a classe `NodeServer`.
2. Em cada configuração, defina os argumentos no campo *Program arguments*:

#### Node1
```
--port=8000 --id=node1 --peers=http://localhost:8010,http://localhost:8020
```

#### Node2
```
--port=8010 --id=node2 --peers=http://localhost:8000,http://localhost:8020
```

#### Node3
```
--port=8020 --id=node3 --peers=http://localhost:8000,http://localhost:8010
```

- Inicie as três configurações, cada uma em um console separado da IDE.


### 3. Executando pelo terminal
Compile e rode com os comandos abaixo (ajustando o caminho do `.jar` do Gson conforme seu projeto):

```bash
# Compilar
javac -cp gson-2.10.1.jar *.java

# Executar os três nós (cada comando em um terminal separado)
java -cp ".;gson-2.10.1.jar" NodeServer --port=8000 --id=node1 --peers=http://localhost:8010,http://localhost:8020
java -cp ".;gson-2.10.1.jar" NodeServer --port=8010 --id=node2 --peers=http://localhost:8000,http://localhost:8020
java -cp ".;gson-2.10.1.jar" NodeServer --port=8020 --id=node3 --peers=http://localhost:8000,http://localhost:8010
```

- 💡 No Linux/Mac, troque *;* por *:* no classpath (-cp).

## 💻 Comandos disponíveis no console

Dentro do terminal de cada nó, você pode digitar:

- listar → Mostra todas as mensagens armazenadas localmente no nó.

- offline → Coloca o nó em modo offline, ele não recebe replicações.

- online → Coloca o nó em modo online novamente e inicia reconciliação (busca mensagens perdidas nos peers).

- sair → Encerra apenas o input do console (o servidor continua rodando).

- <usuário> → Inicia o fluxo de envio de mensagem.

    - O sistema pedirá senha e depois a mensagem.

    - Se a senha estiver incorreta → rejeita.

    - Se correta → a mensagem é armazenada localmente e replicada para os outros nós.

## 👥 Usuários pré-configurados

| Usuário | Senha     |
| ------- | --------- |
| alice   | password1 |
| bob     | password2 |
| carol   | password3 |

## 📸 Exemplos de uso

- Envio de mensagem
```bash
[node1] Digite um comando ou usuário: 
"alice"
[node1] Senha: 
"password1"
[node1] Digite sua mensagem: 
"Olá do nó 1"
[node1] Mensagem enviada com sucesso! ID=...
```

- Replicação
    - Nos outros nós:
```bash
[node2] Recebeu replicação de node1: "Olá do nó 1"
[node3] Recebeu replicação de node1: "Olá do nó 1"
```

- Falha e reconciliação
```
[node2] offline
[node2] Agora estou OFFLINE (não recebo replicações).
...
[node2] online
[node2] Agora estou ONLINE (voltando a receber replicações).
[node2] Iniciando reconciliação...
[node2] Reconciliação com http://localhost:8000: adicionou 2 mensagens
```

## ✅ O que foi demonstrado

- Arquitetura distribuída com múltiplos nós.

- Replicação assíncrona de mensagens.

- Autenticação básica de usuários.

- Consistência eventual com simulação de falhas.

- Esse projeto pode ser expandido futuramente para adicionar:

    - Interface gráfica ou web.

    - Persistência em banco de dados.

    - Mecanismos de autenticação mais robustos.

---