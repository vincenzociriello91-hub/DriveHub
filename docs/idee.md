# Idee per le prossime funzioni

Elenco tenuto da Claude. Vincenzo ha chiesto il 07/11/2026 che DriveHub riceva nuove funzioni in autonomia,
senza doverle chiedere: nelle sessioni "nuove funzioni" (domenica e mercoledì) Claude ne sceglie e costruisce
almeno una da qui (o una nuova idea sua), la segna come fatta e aggiunge idee nuove.

Criteri di scelta: utile a chi guida la Fiesta ogni giorno, sicura (niente che chieda di guardare o toccare lo
schermo in movimento), realizzabile senza chiavi o servizi a pagamento, coerente con quello che dicono i rapporti.

## Da fare
- [ ] Carburante lungo il tragitto casa-lavoro abituale (dai viaggi registrati), non solo vicino a te.
      Prima serve salvare partenza e arrivo dei viaggi (oggi si salvano solo km e litri).
- [ ] Tastiera semplice sul display dell'auto, solo a veicolo fermo, in aggiunta alla dettatura.
- [ ] Riquadro (tile) sul Pixel Watch con consumo del viaggio e prossimo distributore.
      Serve un'app Wear OS separata da installare sull'orologio (debug wireless): chiedere a Vincenzo prima.
- [ ] Dati dell'auto dai server Ford (posizione, carburante, chilometri anche ad auto spenta) tramite l'app Ford:
      sperimentale, le API non sono ufficiali; solo con accesso dell'utente nell'app, mai credenziali nel codice.
- [ ] Verifica con gli adattatori Android Auto senza fili (AAWireless, Motorola MA1 e simili) sul SYNC 2.5,
      che di suo accetta solo il cavo.
- [ ] Tema del display anche con il sensore di luce del telefono (gallerie di giorno), se il Pixel sul supporto lo vede scoperto.
- [ ] Colori giorno/notte anche nel cruscotto e nella barra disegnati da ScreenController, non solo nel launcher.
- [ ] Promemoria vocale "luci accese?" all'imbrunire quando si parte (solo voce, niente schermo).

## Fatte
- [x] 3.0.0 – schermo diviso, voce, avvisi, viaggi, parcheggio, dashcam, avvio automatico.
- [x] 3.1.0 – codici errore spiegati e cancellazione, dettatura, pressione lunga.
- [x] 3.6.0 – app sul telefono a schede (Auto, Diagnosi, Viaggi, Altro) con lista "Prima di partire".
- [x] 3.7.0 – rifornimenti e autonomia, scadenze per data, comandi vocali parcheggio/tagliando/autonomia, ultime app in cima.
- [x] 3.9.0 – colori del display dell'auto chiari di giorno e scuri di notte (alba e tramonto calcolati dalla posizione).
- [x] 3.8.0 – stile di guida con punteggio eco, grafico km e consumi per settimana e mese, barra in alto del display con meteo, autonomia e avvisi.
