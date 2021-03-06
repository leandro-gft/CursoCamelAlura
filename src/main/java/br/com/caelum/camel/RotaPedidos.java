package br.com.caelum.camel;

import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;
import org.apache.camel.impl.DefaultCamelContext;

public class RotaPedidos {

	public static void main(String[] args) throws Exception {

		CamelContext context = new DefaultCamelContext();
		context.addComponent("activemq", ActiveMQComponent.activeMQComponent("tcp://localhost:61616/"));
		
		context.addRoutes(new RouteBuilder() {
			
			@Override
			public void configure() throws Exception {
				
				errorHandler(deadLetterChannel("activemq:queue:pedidos.DLQ").
					logExhaustedMessageHistory(true).
						maximumRedeliveries(3).			//maximas tentativas
							redeliveryDelay(2000).			//tempo entre as tentativas
								onRedelivery(new Processor() {
							
									@Override
									public void process(Exchange exchange) throws Exception {
										int counter = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
										int max = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);
												System.out.println("Redelivery " + counter + "/" + max);
									}
								})
							);			
						
				from("activemq:queue:pedidos"). //pasta de origem, o noop � utilizado para que os arquivos n�o sejam apagados da pasta de origem
					routeId("roda-pedidos").
					to("validator:pedido.xsd").
					multicast().
						to("direct: soap").
						to("direct: http");
				
				from("direct: http").
			//	log("${body}").
				//Ap�s o split n�o existe mais a id do pedido no XML nem a do pagamento. Por isso, colocamos as primeiras duas chamadas do setProperty no in�cio da rota:
				setProperty("pedidoId", xpath("/pedido/id/text()")).
				setProperty("clienteId", xpath("/pedido/pagamento/email-titular/text()")).
				split().			//separa a mensagem por item
					xpath("/pedido/itens/item").	//navega por entre as tags no formato xml
				filter().			//filtra apenas os EBOOKS
					xpath("/item/formato[text()='EBOOK']").
				setProperty("ebookId", xpath("/item/livro/codigo/text()")).
				marshal().xmljson().//transforma arquivo xml em json
				//log("${id} - ${body}").	//imprime o id e corpo das mensagens no console
				setHeader(Exchange.HTTP_METHOD, HttpMethods.GET).
				setHeader(Exchange.HTTP_QUERY, simple("ebookId=${property.ebookId}&pedidoId=${property.pedidoId}&clienteId=${property.clienteId}")).
				
			//	setHeader(Exchange.FILE_NAME, simple("${file:name.noext}-${header.CamelSplitIndex}.json")). //renomeia os arquivos tirando a extens�o original e substituindo por .json
				// o ${header.CamelSplitIndex} foi utilizado para separar cada EBOOK em um json diferente.
				to("http4://localhost:8080/webservices/ebook/item");	//pasta de destino
			
				from("direct: soap").		//direct � como uma subrota
					routeId("roda-soap").
					to("xslt:pedido-para-soap.xslt").
					log("${body}").
					setHeader(Exchange.CONTENT_TYPE , constant("text/xml")).
				to("http4://localhost:8080/webservices/financeiro");
		
				
				
			}
		});
		
		context.start();
		Thread.sleep(20000);
		context.stop();

	}	
}
