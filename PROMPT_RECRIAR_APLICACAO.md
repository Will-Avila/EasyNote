# Prompt mestre para recriar a aplicação Android `notas`

> Use este documento como prompt para um agente de desenvolvimento recriar a aplicação completa descrita abaixo. O objetivo é produzir uma aplicação Android nativa funcional, persistente, segura e verificável — não um protótipo visual ou um conjunto de stubs.

---

## Instrução principal

Recrie do zero uma aplicação Android nativa de anotações chamada **`notas`**, com identidade visual própria e original. A aplicação deve ser offline-first, funcionar sem conta obrigatória ou servidor próprio e armazenar as notas localmente em um banco criptografado.

Implemente todas as funcionalidades, regras de negócio, telas, estados, migrações, segurança, backup, lembretes, notificações e testes descritos neste documento.

Não copie marcas, logos, textos, ícones, assets ou código proprietário de outros aplicativos. O resultado deve ter identidade própria, embora possa implementar comportamentos comuns de aplicativos de notas coloridas.

Não entregue telas falsas, métodos vazios, dados mockados, respostas hardcoded ou funcionalidades apenas aparentes. Cada ação visível deve funcionar e sobreviver ao fechamento e reabertura do aplicativo.

---

## 1. Plataforma, projeto e restrições técnicas

Crie um projeto Android nativo com as seguintes características:

- Linguagem: Kotlin.
- UI: Views Android programáticas; não depender de Compose.
- Componentes visuais: AndroidX e Material 3/Material Components.
- Package/application ID: `com.will.noteharbor`.
- Namespace: `com.will.noteharbor`.
- Nome exibido: `notas`.
- SDK mínimo: 26.
- SDK de compilação: 35.
- Target SDK: 35.
- Java/JVM: 17.
- Android Gradle Plugin compatível com 8.7.3.
- Kotlin compatível com 2.0.21.
- Gradle Wrapper: 8.9.
- Banco local: Room sobre SQLCipher.
- Criptografia de segredos locais: Android Keystore.
- Não usar serviço foreground permanente para lembretes.
- Não usar alarmes exatos; usar `AlarmManager.setAndAllowWhileIdle`.
- Não adicionar servidor, login ou API remota obrigatória.
- Não criar notas de demonstração, exemplos, seed, fixtures ou dados iniciais em produção.

Organize o projeto em camadas claras:

- **Presentation/UI:** `MainActivity`, telas, diálogos, Views, navegação e listeners finos.
- **State:** `NotesViewModel` e estados de tela.
- **Domain:** modelos, filtros, parser de checklist, preview, segurança, regras de lembrete, merge e políticas testáveis sem Android.
- **Data:** repository, Room, SQLCipher, migrações, codec legado e sincronização.
- **Reminder:** scheduler, receivers, notificações e recuperação de alarmes.
- **Resources:** strings, temas, cores, drawables vetoriais, regras de backup e ícone.

Use recursos e drawables próprios. Não use pictogramas Unicode como ícones funcionais; use vetores Android com `contentDescription` e dimensões explícitas.

---

## 2. Instalação inicial e estado vazio

Uma instalação limpa deve iniciar com o banco vazio.

Regras obrigatórias:

1. Nunca inserir notas de exemplo automaticamente.
2. Nunca criar títulos, textos, checklists ou lembretes demonstrativos.
3. Se não existir armazenamento legado, inicializar com uma lista vazia.
4. Se o armazenamento legado estiver ausente ou inválido, descartar somente o legado inválido e inicializar com uma lista vazia.
5. Se existirem dados legados válidos, migrá-los preservando seu conteúdo, IDs, estado, timestamps, cores, proteção e lembretes.
6. Não usar `demoNotes()`, `sampleNotes()`, `seed()`, `prepopulate()` ou fallback equivalente.
7. Remover completamente títulos e IDs de exemplos da base de código.
8. Adicionar teste de regressão que prove `null -> lista vazia` e `legado válido -> notas preservadas`.

Quando não houver notas, mostrar um estado vazio amigável, por exemplo:

- Título: `Tudo limpo por aqui`.
- Mensagem: `Crie uma nota para começar a tirar ideias da cabeça.`.
- O botão flutuante deve continuar disponível para criar a primeira nota.

---

## 3. Modelo de dados de uma nota

Implemente um modelo equivalente a:

```kotlin
data class Note(
    val id: String,
    val title: String,
    val body: String = "",
    val type: NoteType = NoteType.TEXT,
    val color: NoteColor = NoteColor.SUN,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val locked: Boolean = false,
    val passwordHash: String = "",
    val updatedAt: Long,
    val updatedBy: String,
    val items: List<ChecklistItem> = emptyList(),
    val reminder: ReminderSchedule? = null,
)
```

Tipos:

```kotlin
enum class NoteType {
    TEXT,
    CHECKLIST,
}

data class ChecklistItem(
    val text: String,
    val completed: Boolean = false,
)
```

Regras:

- Cada nota possui ID estável, gerado na criação.
- O título deve ser normalizado com `trim()`.
- Se o título estiver vazio:
  - nota textual recebe o título padrão `Nota`;
  - checklist recebe o título padrão `Lista`.
- Uma nota textual persiste o conteúdo em `body` e não possui itens.
- Uma checklist persiste os itens e mantém `body` vazio.
- Uma checklist é considerada concluída quando possui itens e todos estão marcados.
- `pinned` altera a ordem e aparece visualmente como fixada.
- `archived` oculta a nota das listas normais, mas não deve desativar seus lembretes.
- `updatedAt` deve ser atualizado a cada alteração persistida.
- `updatedBy` deve identificar o dispositivo local ou a origem da sincronização.

### Cores das notas

Ofereça seis cores selecionáveis:

| Código | Nome | Fundo claro | Acento claro | Fundo escuro | Acento escuro |
|---|---|---|---|---|---|
| `SUN` | Sol | `#FFF1B8` | `#D89216` | `#403417` | `#F4C95D` |
| `PEACH` | Pêssego | `#FFD9C7` | `#D26947` | `#482D23` | `#F4A07A` |
| `MINT` | Menta | `#CDEFE5` | `#2B8A78` | `#173C35` | `#8CE0CC` |
| `LAVENDER` | Lavanda | `#E3DDF8` | `#6557B5` | `#302A50` | `#BFB5FF` |
| `SKY` | Céu | `#D7ECFA` | `#32799E` | `#1B3846` | `#8CD0F4` |
| `ROSE` | Rosa | `#F8D5DE` | `#B34C69` | `#452834` | `#F0A4B9` |

No seletor de cor:

- usar preenchimentos opacos;
- não mostrar símbolo de check ao selecionar uma cor;
- indicar a seleção somente por borda mais espessa e elevação;
- manter contraste adequado nos modos claro e escuro;
- não comunicar seleção apenas por transparência.

---

## 4. Parsing e apresentação de checklists

No editor de checklist, aceitar um item por linha.

O parser deve:

- remover espaços laterais;
- ignorar linhas vazias;
- remover prefixos `- `, `[ ] `, `[x] ` e `[X] `;
- preservar o estado concluído dos itens anteriores por posição quando a nota for editada;
- produzir `ChecklistItem` limpo para cada linha válida.

No card:

- não mostrar todos os itens em notas protegidas;
- mostrar apenas o progresso, por exemplo `2 de 4 concluídos`;
- mostrar `PRONTO` quando todos os itens estiverem completos e a nota não estiver protegida;
- limitar a prévia para não aumentar indefinidamente a altura do card.

No visualizador:

- mostrar barra de progresso;
- mostrar todos os itens;
- permitir marcar/desmarcar cada item;
- persistir a alteração imediatamente;
- aplicar aparência atenuada ao item concluído;
- atualizar o progresso após cada marcação.

---

## 5. Consultas, busca, filtros e ordenação

Implemente três filtros principais:

- `Todas`: todas as notas não arquivadas;
- `Fixadas`: notas não arquivadas e fixadas;
- `Listas`: notas não arquivadas do tipo checklist.

A busca deve procurar, sem diferenciar maiúsculas/minúsculas, em:

- título;
- corpo da nota;
- textos dos itens da checklist.

Ordenação:

1. notas fixadas primeiro;
2. depois por `updatedAt` decrescente.

O texto de resumo deve informar:

- `Nenhuma nota encontrada`;
- `1 nota visível`;
- ou `N notas visíveis`.

---

## 6. Tela inicial

Crie a tela inicial com Views programáticas e layout edge-to-edge.

### Cabeçalho

- Marca pequena: `NOTAS`.
- Título grande: `Seu espaço para pensar`.
- Subtítulo: `Uma ideia por vez. O resto encontra seu lugar.`.
- Botão de menu no canto superior direito com ícone vetorial de três pontos.
- Botão com área de toque confortável e `contentDescription="Abrir menu"`.

### Pesquisa

- Campo com dica `Buscar em tudo`.
- Ícone de pesquisa.
- Borda visível e superfície própria.
- Texto e hint legíveis em ambos os temas.
- Atualização da lista enquanto o usuário digita.

### Filtros

Mostrar chips/botões:

- `Todas`;
- `Fixadas`;
- `Listas`.

O filtro selecionado deve possuir fundo/texto claramente distintos.

### Resumo de origem

Mostrar a contagem das notas e o estado local:

```text
LOCAL · SEM NUVEM
```

Quando houver backup configurado, manter o indicador compatível com o estado da sincronização, sem ocultar o funcionamento offline.

### Lista e rolagem

- Usar `ScrollView` vertical.
- Ocultar barras de rolagem horizontal e vertical.
- Preservar rolagem e insets.
- Deixar espaço inferior suficiente para o FAB.
- Aplicar `WindowInsetsCompat` para barras do sistema, recorte da tela e teclado.

### FAB

- Botão flutuante no canto inferior direito.
- Dimensão aproximada: `60dp x 60dp`.
- Ícone de adicionar com tamanho aproximado de `32dp`.
- Sem padding interno acidental.
- Cor de destaque própria para o tema.
- `contentDescription="Nova nota"`.
- Abre o editor de uma nota nova.

---

## 7. Cards de notas

Cada card deve ser arredondado, colorido de acordo com a nota e sem barra/acento lateral.

Regras visuais:

- fundo usando a variante clara/escura da cor da nota;
- cantos arredondados de aproximadamente `22dp`;
- elevação discreta;
- conteúdo interno com espaçamento confortável;
- nenhum acento vertical colorido na lateral;
- altura determinada pelo conteúdo limitado.

Conteúdo:

1. etiqueta `NOTA` ou `CHECKLIST`;
2. título em destaque, uma linha, truncado no final;
3. ações pequenas:
   - fixar/desafixar;
   - copiar;
   - compartilhar;
   - excluir;
4. conteúdo:
   - nota protegida: `CONTEÚDO PROTEGIDO` e mensagem para digitar a senha;
   - checklist: progresso;
   - nota textual: prévia curta do corpo;
5. rodapé com data/hora da última alteração;
6. `PRONTO` para checklist concluída;
7. rodapé adicional quando houver lembrete, com texto legível, por exemplo:
   - `Lembrete · uma vez em 13/08/2026 às 14:28`;
   - `Lembrete · seg, qua e dom às 18:30`;
   - `Lembrete · dia 15 de cada mês às 07:05`.

Ações dos cards:

- tocar no card abre o visualizador;
- tocar em fixar altera o estado e persiste imediatamente;
- copiar e compartilhar passam pela barreira de desbloqueio se a nota for protegida;
- excluir abre confirmação personalizada;
- lembretes de notas arquivadas continuam agendados.

---

## 8. Visualizador de nota

Ao tocar em uma nota não protegida, abrir uma tela dedicada de visualização.

Ao tocar em uma nota protegida:

1. mostrar diálogo `Nota protegida`;
2. solicitar a senha;
3. permitir mostrar/ocultar a senha com ícone de olho;
4. rejeitar senha incorreta sem fechar o diálogo;
5. só depois da senha correta exibir o conteúdo;
6. não vazar corpo, itens ou conteúdo sensível antes do desbloqueio.

Tela do visualizador:

- toolbar com voltar;
- título `Visualizar nota`;
- botão `Editar`;
- superfície com a cor da nota;
- título, tipo e conteúdo completo;
- checklist interativa com progresso e barra horizontal;
- corpo textual selecionável;
- data/hora de atualização;
- ações de copiar, compartilhar e excluir.

Ao editar a partir do visualizador e salvar:

- recarregar a nota persistida;
- retornar ao visualizador da mesma nota;
- não voltar silenciosamente para uma lista desatualizada.

---

## 9. Editor de notas

Crie uma tela dedicada, não um formulário improvisado dentro da lista.

Toolbar:

- voltar;
- título `Nova nota` ou `Editar nota`;
- botão `Salvar`.

Campos e controles:

1. seletor de tipo:
   - `Nota`;
   - `Checklist`.
2. campo `Título`;
3. campo de conteúdo:
   - placeholder para texto: `Escreva o que está na sua cabeça`;
   - placeholder para checklist: `Um item por linha`;
4. seletor `Cor da nota`;
5. opção `Exigir senha para visualizar`;
6. opção `Ativar lembrete`.

Regras do salvamento:

- rejeitar nota sem título e sem conteúdo;
- aplicar título padrão quando necessário;
- preservar ID, estado fixado e estado arquivado ao editar;
- salvar primeiro no banco;
- somente depois publicar o novo estado para a UI e reconciliar os lembretes;
- atualizar `updatedAt` e `updatedBy`;
- mostrar a nota salva imediatamente na tela correta;
- não perder o lembrete por depender do estado visual transitório de um checkbox.

### Proteção por senha

Ao ativar a proteção, abrir modal próprio:

- nota nova: exigir senha com pelo menos 8 caracteres;
- nota existente protegida: permitir informar nova senha ou deixar vazio para manter a atual;
- permitir mostrar/ocultar senha;
- aplicar a proteção somente após confirmação;
- desmarcar a opção remove a proteção e limpa o hash no próximo salvamento;
- não armazenar senha em texto puro.

### Lembrete no editor

O `ReminderSchedule?` é a única fonte de verdade.

- o checkbox é apenas uma projeção de `schedule != null`;
- confirmar o modal grava o schedule no estado do editor;
- cancelar preserva o schedule anterior;
- desmarcar remove explicitamente o schedule;
- o salvamento lê o schedule diretamente, nunca usa o checkbox como gate adicional;
- o card deve refletir o lembrete persistido depois do salvamento.

Para um lembrete novo, iniciar o modal com:

- data de hoje;
- horário do próximo minuto disponível;
- exemplo: se agora for `14:27:42`, iniciar em `14:28`.

Não iniciar automaticamente em `09:00` nem no dia seguinte.

---

## 10. Tema claro e escuro

Persistir a escolha do tema e recriar a Activity ao alternar.

### Paleta clara

- canvas: `#FAF8F4`;
- texto principal: `#1F2430`;
- texto secundário: `#566273`;
- texto discreto: `#647083` / `#748092`;
- superfície de input: `#E4E8EE`;
- borda de input: `#B7C0CC`;
- destaque: `#2B8A78`;
- destaque suave: `#CDEFE5`;
- FAB: `#4F46E5`;
- superfície de diálogo: `#FFFFFF`.

### Paleta escura

- canvas: `#15171D`;
- texto principal: `#F6F7FA`;
- texto secundário: `#CDD3DE`;
- texto discreto: `#B8C1CE` / `#88919F`;
- superfície de input: `#2D333E`;
- borda de input: `#596575`;
- destaque: `#8CE0CC`;
- destaque suave: `#244E47`;
- FAB: `#8B5CF6`;
- superfície de diálogo: `#24262E`.

Regras:

- todos os Views programáticos devem receber cores da paleta ativa;
- títulos, hints, botões, checkboxes, radio buttons e diálogos devem ter contraste suficiente;
- configurar barras de status/navegação e ícones claros/escuros;
- não deixar texto escuro em superfície escura;
- não usar cores fixas de tema claro em Views dinâmicos.

Menu principal:

- em tema claro, item `Tema escuro` com ícone de lua;
- em tema escuro, item `Tema claro` com ícone de sol;
- item `Backup na nuvem` com ícone próprio;
- menu com dois itens verticais, áreas clicáveis completas e largura estável.

---

## 11. Persistência local criptografada

Use Room + SQLCipher para o banco local `notas.db`.

Entidades mínimas:

### `notes`

Campos:

- `id` chave primária;
- `title`;
- `body`;
- `type`;
- `color`;
- `pinned`;
- `archived`;
- `locked`;
- `passwordHash`;
- `updatedAt`;
- `updatedBy`;
- `reminderRecurrence` nullable;
- `reminderHour` nullable;
- `reminderMinute` nullable;
- `reminderDayOfWeek` nullable para compatibilidade;
- `reminderDaysOfWeek` nullable;
- `reminderDayOfMonth` nullable;
- `reminderDate` nullable.

### `checklist_items`

- chave composta `noteId + position`;
- chave estrangeira para `notes` com cascade;
- `text`;
- `completed`.

### `note_tombstones`

- `noteId` chave primária;
- `deletedAt`.

### `database_metadata`

- `key` chave primária;
- `value`.

Configuração:

- Room database version 3;
- SQLCipher `SupportOpenHelperFactory`;
- migrations explícitas 1 -> 2 e 2 -> 3;
- não usar destructive migration;
- operações de snapshot em transação;
- remover e inserir itens de checklist de forma consistente;
- sincronizar tombstones;
- fechar o banco e limpar byte arrays de passphrase quando o repository for encerrado.

### Migrações

Migração 1 -> 2:

- adicionar recorrência;
- hora;
- minuto;
- dia da semana legado;
- dia do mês.

Migração 2 -> 3:

- adicionar múltiplos dias da semana;
- adicionar data específica.

Campos ausentes devem significar lembrete desativado. Dados opcionais malformados devem ser ignorados para aquela nota, sem apagar o conjunto inteiro.

---

## 12. Segurança local

### Senhas das notas

- gerar salt aleatório por nota;
- não persistir senha em texto puro;
- persistir somente representação de salt + digest;
- comparar com operação em tempo constante;
- senha incorreta nunca deve revelar conteúdo;
- exportação, cópia, compartilhamento, preview, visualização e checklist protegidos devem passar pelo mesmo gate de desbloqueio;
- cards protegidos mostram apenas metadados e aviso genérico.

### Android Keystore

Crie uma classe de armazenamento seguro usando:

- chave AES no `AndroidKeyStore`;
- `AES/GCM/NoPadding`;
- IV novo por valor salvo;
- segredo armazenado em SharedPreferences somente na forma cifrada.

Guardar no armazenamento protegido:

- ID do dispositivo;
- chave/passphrase do banco local;
- passphrase derivada/configuração do backup remoto.

Nunca imprimir, versionar, incluir no README ou registrar em logs:

- senhas;
- tokens;
- chaves;
- passphrases;
- conteúdo de notas protegidas.

---

## 13. Backup criptografado na nuvem

O backup é opcional e não deve impedir o uso local.

### Fluxo de interface

No menu, item `Backup na nuvem`.

Se não configurado:

- `Criar novo arquivo`;
- `Usar arquivo existente`.

Se configurado:

- mostrar estado do arquivo;
- checkbox `Sincronizar a cada modificação`;
- botão `Sincronizar agora`;
- botão `Trocar arquivo`;
- botão `Desconectar`.

Ao desconectar:

- manter todas as notas locais;
- não apagar o arquivo remoto;
- liberar permissões persistentes do URI quando possível;
- limpar a configuração local do backup.

### Senha do backup

- exigir pelo menos 8 caracteres;
- ao criar, pedir confirmação;
- ao abrir arquivo existente, pedir a senha usada na criação;
- nunca persistir a senha em texto puro;
- permitir providers do Storage Access Framework, como Google Drive, OneDrive e Dropbox quando disponíveis no aparelho.

### Formato remoto

O arquivo remoto não é JSON nem uma cópia viva do banco local. Criar um envelope binário com:

- magic `NOTASDB`;
- versão do formato;
- salt aleatório de 16 bytes ou maior;
- bytes de um banco SQLCipher fechado;
- limite máximo de aproximadamente 64 MiB;
- rejeição de arquivo vazio, magic inválido, versão inválida, salt inválido, tamanho inválido ou dados extras.

Derivar a chave do backup com:

- PBKDF2-HMAC-SHA256;
- salt do envelope;
- 210.000 iterações;
- chave de 256 bits.

Ao sincronizar:

1. ler o arquivo remoto;
2. decodificar e abrir o banco temporário SQLCipher;
3. carregar o snapshot remoto;
4. mesclar local e remoto por ID;
5. considerar `updatedAt` e `updatedBy` para desempate determinístico;
6. respeitar tombstones de exclusão;
7. gravar o snapshot mesclado no repositório local;
8. criar um novo banco SQLCipher temporário;
9. escrever o envelope criptografado no URI escolhido;
10. remover arquivos temporários, incluindo `-wal`, `-shm` e `-journal`;
11. limpar byte arrays sensíveis em todos os caminhos.

### Sincronização automática

- disparar após toda alteração local persistida;
- opcionalmente sincronizar ao abrir o aplicativo;
- execução automática silenciosa, sem Toast nem spinner obrigatório;
- sincronização manual pode informar sucesso ou erro;
- impedir duas sincronizações simultâneas;
- se uma alteração ocorrer durante sincronização, marcar pendência e executar uma nova sincronização após a atual terminar;
- nunca perder a última alteração.

---

## 14. Lembretes e recorrência

Modele o lembrete como nullable `ReminderSchedule`.

```kotlin
data class ReminderSchedule(
    val recurrence: ReminderRecurrence,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int> = emptySet(),
    val dayOfMonth: Int? = null,
    val date: LocalDate? = null,
)
```

Tipos internos:

```kotlin
enum class ReminderRecurrence {
    DAILY,    // legado: legível, não oferecido na UI atual
    WEEKLY,
    MONTHLY,
    ONCE,
}
```

Validações:

- hora entre 0 e 23;
- minuto entre 0 e 59;
- dias ISO entre 1 e 7;
- semanal exige pelo menos um dia;
- mensal exige dia entre 1 e 31;
- único exige data;
- combinações incompatíveis devem falhar claramente.

### Semanal

- usar conjunto de dias ISO:
  - segunda = 1;
  - terça = 2;
  - quarta = 3;
  - quinta = 4;
  - sexta = 5;
  - sábado = 6;
  - domingo = 7.
- na UI, exibir na ordem `Dom`, `Seg`, `Ter`, `Qua`, `Qui`, `Sex`, `Sáb`;
- permitir múltiplos dias;
- não permitir que a seleção fique sem nenhum dia.

### Mensal

- permitir dias 1 a 31;
- em meses curtos, usar o último dia disponível;
- por exemplo, dia 31 em fevereiro dispara no último dia de fevereiro.

### Único

- armazenar data específica;
- preservar o lembrete até a notificação ser publicada com sucesso;
- depois da publicação bem-sucedida, remover o lembrete persistido para não repetir;
- se a data passou por falha temporária, não apagar imediatamente: permitir retry/reagendamento.

### Compatibilidade diária

- aceitar e ler lembretes `DAILY` salvos em versões antigas;
- não oferecer `Diária` no editor atual;
- converter somente a apresentação necessária sem apagar dados legados válidos.

### Modal de lembrete

Título: `Configurar lembrete`.

Seções:

1. `Horário` com botão de hora;
2. `Repetição` com:
   - `Lembrar uma vez`;
   - `Semanal`;
   - `Mensal`;
3. `Dias da semana`, visível para semanal;
4. `Dia do mês`, visível para mensal;
5. `Data específica`, visível para único.

O modal deve:

- ocultar controles irrelevantes para o modo atual;
- permitir selecionar hora com um seletor digital em modo de entrada por teclado, no formato de 24 horas, sem relógio analógico;
- permitir selecionar data com `DatePickerDialog`;
- validar o futuro antes de confirmar;
- manter estado anterior ao cancelar;
- usar superfícies, bordas e textos legíveis nos dois temas;
- esconder barras de rolagem do conteúdo interno.

---

## 15. Notificações e entrega em background

Use `AlarmManager` + `BroadcastReceiver`.

### Canal

Criar o canal:

- ID: `lembretes_v3`;
- nome: `Lembretes`;
- importância alta;
- vibração habilitada;
- padrão de vibração curto;
- badge habilitado;
- descrição explicando que são lembretes das notas.

Usar ID versionado porque a importância/configuração de um canal já existente não pode ser elevada programaticamente.

### Permissão

No Android 13+:

- declarar `POST_NOTIFICATIONS`;
- solicitar quando o usuário ativa um lembrete;
- solicitar ao abrir/retomar o app se já existirem lembretes ativos e a permissão ainda não tiver sido concedida;
- verificar também notificações globais do aplicativo;
- verificar se o canal está bloqueado;
- oferecer link direto para configurações do aplicativo/canal quando bloqueado;
- manter o lembrete persistido mesmo sem permissão.

### Agendamento

Usar:

```kotlin
AlarmManager.setAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP,
    triggerAtMillis,
    pendingIntent,
)
```

Não usar `setExact`, `setExactAndAllowWhileIdle` nem serviço foreground permanente.

O scheduler deve:

- reconciliar todo o conjunto persistido de notas;
- cancelar IDs antigos;
- separar identidade de alarme normal, retry e reconciliação;
- usar `PendingIntent` imutável;
- reagendar recorrentes após cada disparo;
- remover lembrete único somente após publicação confirmada;
- manter lembretes de notas arquivadas;
- registrar erros úteis sem conteúdo sensível.

### Receiver do alarme

O `ReminderAlarmReceiver` deve:

1. ler o ID da nota;
2. chamar `goAsync()`;
3. abrir o repository em executor de background;
4. carregar a nota persistida novamente;
5. se a nota não existir ou não tiver lembrete, cancelar o alarme;
6. verificar permissão, app e canal;
7. publicar a notificação;
8. em falha, agendar retry;
9. em lembrete único publicado, cancelar o alarme e limpar o lembrete do banco;
10. em recorrente, reagendar a próxima ocorrência;
11. fechar o repository;
12. chamar `pendingResult.finish()` em todos os caminhos.

### Conteúdo da notificação

Nota normal:

- título: título da nota;
- corpo genérico: `Seu lembrete está aguardando você.`.

Nota protegida:

- título genérico: `Lembrete de nota protegida`;
- corpo: `Toque para abrir e desbloquear a nota.`;
- nunca expor título ou conteúdo sensível.

Ao tocar na notificação:

- abrir ou trazer a Activity existente com `singleTop`;
- transportar o ID da nota;
- abrir a nota e exigir senha se estiver protegida.

### Retry

Para falhas temporárias de banco, permissão, canal ou publicação:

- manter o lembrete persistido;
- tentar novamente aproximadamente 15 minutos depois;
- limitar a quatro tentativas;
- usar `PendingIntent`/request code separado do alarme normal;
- registrar quando o limite for atingido.

### Reagendamento do sistema

Declarar receiver para:

- `BOOT_COMPLETED`;
- `MY_PACKAGE_REPLACED`;
- `TIME_SET`;
- `TIMEZONE_CHANGED`;
- `USER_UNLOCKED`;
- retry interno de reconciliação.

Após esses eventos:

- abrir o banco em background;
- carregar notas persistidas;
- reconciliar os alarmes;
- se o banco falhar, agendar nova tentativa de reconciliação.

---

## 16. Manifest e backup do Android

Manifest mínimo necessário:

- `POST_NOTIFICATIONS`;
- `RECEIVE_BOOT_COMPLETED`;
- Activity principal exportada e lançável;
- `launchMode="singleTop"`;
- receiver do alarme não exportado;
- receiver de reagendamento não exportado;
- filtros para boot, pacote atualizado, horário, fuso e usuário desbloqueado;
- ícone original próprio;
- tema claro/escuro.

Configurar backup para excluir:

- `notas.db`;
- WAL/SHM/journal do banco;
- SharedPreferences legadas;
- preferências de backup;
- preferências de segredos do Keystore.

Não permitir que material protegido seja copiado como backup não criptografado do Android.

---

## 17. Diálogos, acessibilidade e detalhes visuais

Todos os diálogos devem:

- usar superfície compatível com o tema;
- ter largura limitada e estável;
- possuir título, descrição e botões legíveis;
- evitar animações/refluxos que movam o diálogo após o primeiro frame;
- aplicar contraste explicitamente após `show` quando necessário;
- manter botão positivo, negativo e destrutivo claramente distinguíveis.

Ações sem texto devem ter:

- `contentDescription`;
- tamanho mínimo de toque confortável;
- ícone vetorial explícito;
- ripple/background adequado.

Não usar emojis para representar sol, lua, voltar, check, excluir, copiar, compartilhar ou outras ações.

---

## 18. Testes obrigatórios

Escreva testes unitários para regras que não dependem de Activity.

Cobrir no mínimo:

### Domínio

- título padrão de nota e checklist;
- parser de checklist;
- preservação de itens concluídos;
- progresso de checklist;
- preview textual limitado;
- busca e filtros;
- ordenação por fixação e atualização;
- merge local/remoto;
- tombstones;
- política de instalação vazia;
- seleção de lembrete e checkbox;
- cálculo do próximo minuto padrão.

### Lembretes

- validação de horário;
- recorrência semanal com vários dias;
- ordem ISO dos dias;
- passagem de semana;
- recorrência mensal;
- meses curtos e dias 29–31;
- lembrete único futuro;
- lembrete único expirado;
- compatibilidade com lembrete diário legado;
- política de permissão;
- distinção de IDs de alarmes normal, retry e reconciliação.

### Persistência

- mapeamento Note -> Room -> Note;
- checklist e foreign key;
- reminders nullable;
- migração 1 -> 2;
- migração 2 -> 3;
- codec JSON completo;
- codec JSON legado sem lembrete;
- dados opcionais malformados sem apagar as demais notas.

### Segurança

- senha correta desbloqueia;
- senha incorreta falha;
- hash não contém a senha original;
- salt diferente para hashes diferentes;
- proteção persiste no codec;
- conteúdo protegido não é incluído em texto de notificação.

### Backup

- derivação de chave;
- envelope válido;
- rejeição de magic inválido;
- rejeição de versão inválida;
- rejeição de arquivo vazio;
- rejeição de tamanho/salt inválidos;
- round-trip do envelope;
- limpeza de dados temporários quando aplicável.

Não afirmar que foi executada uma fase RED do TDD se ela não tiver sido realmente observada. Para bugs novos, criar primeiro um teste de regressão, executá-lo e confirmar a falha esperada antes da implementação.

---

## 19. Validação de build e entrega

Executar com o Gradle Wrapper:

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug assembleRelease
```

Depois:

```bash
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

Confirmar:

- package `com.will.noteharbor`;
- label `notas`;
- version code/name;
- min SDK 26;
- target SDK 35;
- permissões de notificação e boot;
- Activity lançável;
- receivers presentes;
- APK debug assinado e verificável;
- release unsigned identificado honestamente como não assinado para distribuição.

Se houver aparelho ou emulador funcional, executar uma jornada real:

1. instalar em estado limpo;
2. abrir e confirmar que não existem notas de exemplo;
3. criar nota textual;
4. fechar e reabrir;
5. editar e alterar cor;
6. criar checklist;
7. marcar itens no visualizador;
8. fixar, pesquisar, copiar, compartilhar e excluir;
9. criar nota protegida e testar senha correta/incorreta;
10. criar lembrete curto;
11. conceder notificações;
12. fechar o aplicativo;
13. observar a notificação;
14. tocar na notificação e abrir a nota;
15. testar lembrete recorrente;
16. configurar backup em arquivo escolhido pelo Storage Access Framework;
17. sincronizar e restaurar em estado limpo.

Se não houver aparelho/emulador, declarar explicitamente que a validação ficou limitada a testes JVM, lint, compilação e inspeção do APK. Não afirmar entrega de notificação ou aparência visual em runtime sem observação real.

---

## 20. Critérios de aceitação finais

A recriação só estará concluída quando:

- a instalação limpa iniciar sem notas salvas;
- a tela vazia permitir criar a primeira nota;
- notas textuais e checklists forem persistidas;
- busca, filtros, ordenação, fixação, cópia, compartilhamento e exclusão funcionarem;
- o visualizador e o editor tiverem navegação consistente;
- a proteção por senha bloquear todo conteúdo sensível;
- tema claro/escuro funcionar e persistir;
- seis cores forem selecionáveis e legíveis;
- dados locais usarem Room + SQLCipher;
- segredos usarem Android Keystore;
- migrações preservarem dados legados válidos;
- backup criptografado funcionar via SAF;
- merge e tombstones impedirem reaparecimento de notas excluídas;
- lembretes únicos, semanais e mensais funcionarem conforme as regras;
- lembretes diários legados continuarem legíveis, mas não apareçam como opção nova;
- notas protegidas não vazarem informação nas notificações;
- a permissão Android 13+ seja tratada corretamente;
- alarmes sejam reconstruídos após boot, atualização, fuso, horário e desbloqueio;
- falhas de publicação mantenham o lembrete e usem retry limitado;
- testes, lint e builds terminem com resultado real registrado;
- nenhum dado de exemplo permaneça no código ou seja criado na instalação.

Ao finalizar, informe exatamente:

1. arquivos criados/alterados;
2. comandos executados;
3. resultado dos testes;
4. resultado do lint;
5. resultado dos builds;
6. caminho dos APKs;
7. o que foi validado em dispositivo;
8. o que permaneceu sem validação por falta de aparelho/emulador.
