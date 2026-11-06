# А1. C4 Level 1 - Context Diagram

## Чего не хватало в проекте

В исходной документации (`docs/architecture.md`) границы системы "СМП vs. внешний мир" явно не проведены:
отсутствовала отдельная Context-диаграмма, не были специфицированы внешние акторы, точки сочленения и протоколы входа каждого участника.

## Что сделано

Построена Context-диаграмма C4 Level 1, которая строго отделяет ядро СМП от внешних систем/акторов и явно документирует протоколы взаимодействия.

## Диаграмма

```mermaid
graph TB
    subgraph external["Внешние акторы и эмуляторы"]
        terminal["Terminal Simulator<br/>порт :8085"]
        merchantActor["Merchant Acquirer<br/>порт :8084"]
        dashUser["Пользователь дашборда<br/>"]
        binExternal["Внешний BIN Lookup<br/>порт :8096"]
    end

    subgraph smp[" СМП — Симулятор процессингового центра"]
        gateway["Gateway :8080"]
        dashApp["Dashboard :3000"]
    end

    terminal -- "HTTP POST /api/simulator/terminal/**" --> gateway
    merchantActor -- "HTTP POST /api/simulator/merchant/**" --> gateway
    dashUser -- "HTTP :3000" --> dashApp
    dashApp -. "WebSocket /ws/transactions" .-> gateway
    dashApp -- "REST API /api/transactions/**, /api/dashboard/**" --> gateway
    smp -- "HTTP GET /api/bin/{bin}<br/>" --> binExternal

    style smp fill:#ffffff,stroke:#ffffff,stroke-width:3px
    style external fill:#ffffff,stroke:#ffffff, stroke-width:2px
```
