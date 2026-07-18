# Backlog do lint Apex

## Regra de escopo

O lint deve suportar construcoes gerais de Apex e o grafo de classes do workspace. Nenhuma regra, stub ou fixture pode depender do nome ou da API de uma biblioteca, incluindo `fflib`.

Repositorios externos sao corpus de integracao. Um achado so vira teste unitario quando houver um repro minimo, generico e sem schema ou dependencia do cliente.

## P0. Fechar o grafo transitivo no `allx check` (concluido)

**Problema:** `Workspace.check` carrega dependencias diretas e cadeias de heranca, mas nao as dependencias gerais dessas classes. Uma biblioteca Apex presente no workspace pode, portanto, gerar diagnosticos espurios ou um falso limpo.

**Implementado:** o `check` usa a clausura transitiva generica de `Workspace.resolveDepsForSource`, compilando somente as classes alcancaveis a partir do conteudo aberto. Diagnosticos continuam limitados ao arquivo aberto.

**Validacao:** `WorkspaceDependencyClosureTest` cobre uma biblioteca Apex generica de quatro niveis, uma classe nao relacionada e quebrada, e um buffer nao salvo cujo erro String-para-Integer so aparece quando a clausura inteira esta carregada.

## P1. Runner de corpus em lote

**Objetivo:** um comando de teste recebe diretorios de corpus, verifica todas as classes em lote e falha em crash, timeout ou diagnostico interno do AlloyX.

**Criterio de aceite:** diretorios sao passados por configuracao, nao por caminho hardcoded. A execucao nao altera os repositorios de corpus. O runner reutiliza o indice do workspace dentro da mesma execucao. O relatorio informa classes verificadas, diagnosticos por categoria, tempo por classe e falhas internas.

## P2. Baseline de linguagem Apex

Criar fixtures minimos, sem bibliotecas, para cada construcao confirmada no corpus:

1. Operadores e conversoes numericas Apex.
2. Collections, genericos, `Map` e `Iterator`.
3. Classes internas, interfaces, heranca e tipos qualificados.
4. Propriedades, acessos dinamicos e tipos de retorno.
5. Datas, `Datetime`, operadores relacionais e incremento.
6. SOQL, `AggregateResult` e tokens `Schema` que pertencam a sintaxe ou ao modelo de tipos generico.

Cada item so entra apos reduzir o caso a Apex puro. Assinaturas de uma biblioteca ou de uma organizacao nao entram na suite unitaria.

## P3. Gate de regressao

Para cada mudanca no linter:

1. Rodar toda a suite Gradle.
2. Rodar todos os corpus configurados em lote.
3. Anexar ao PR o resumo do corpus e qualquer nova categoria de diagnostico.

Nao ha excecao por biblioteca: se ela for Apex e estiver no workspace, deve funcionar pela resolucao generica do grafo.
