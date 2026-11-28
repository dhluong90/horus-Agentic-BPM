/*
 * The structure symbols of BPMN (#209) in one definition with a stored layout: a pool with nested lanes, a black-box
 * pool and its message flow, a receive task that starts the process, text annotations (one placed by the diagram, one
 * left for the cockpit to place) and a group with its category name.
 */

import type { DemoDefinition } from './processDemo';

export const SYMBOL_STRUCTURE_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_structure" targetNamespace="urn:horus-bpm:demo">
  <bpmn:collaboration id="collab">
    <bpmn:participant id="pool-orders" name="Order to cash" processRef="order-to-cash"/>
    <bpmn:participant id="pool-customer" name="Customer"/>
    <bpmn:messageFlow id="mf-order" name="Order" sourceRef="pool-customer" targetRef="receive-order"/>
  </bpmn:collaboration>
  <bpmn:category id="cat"><bpmn:categoryValue id="cat-fraud" value="Fraud screening"/></bpmn:category>
  <bpmn:process id="order-to-cash" name="Order to cash" isExecutable="true">
    <bpmn:laneSet id="lanes">
      <bpmn:lane id="lane-sales" name="Sales">
        <bpmn:childLaneSet id="lanes-sales">
          <bpmn:lane id="lane-front" name="Front office"><bpmn:flowNodeRef>receive-order</bpmn:flowNodeRef></bpmn:lane>
          <bpmn:lane id="lane-back" name="Back office"><bpmn:flowNodeRef>check-credit</bpmn:flowNodeRef></bpmn:lane>
        </bpmn:childLaneSet>
      </bpmn:lane>
      <bpmn:lane id="lane-finance" name="Finance"><bpmn:flowNodeRef>invoice</bpmn:flowNodeRef><bpmn:flowNodeRef>paid</bpmn:flowNodeRef></bpmn:lane>
    </bpmn:laneSet>
    <bpmn:receiveTask id="receive-order" name="Receive order" instantiate="true"/>
    <bpmn:businessRuleTask id="check-credit" name="Check credit"/>
    <bpmn:serviceTask id="invoice" name="Issue invoice"/>
    <bpmn:endEvent id="paid" name="Invoice sent"/>
    <bpmn:sequenceFlow id="f1" sourceRef="receive-order" targetRef="check-credit"/>
    <bpmn:sequenceFlow id="f2" sourceRef="check-credit" targetRef="invoice"/>
    <bpmn:sequenceFlow id="f3" sourceRef="invoice" targetRef="paid"/>
    <bpmn:textAnnotation id="note-credit"><bpmn:text>The limit comes from the Rule Engine; an order over it goes to a person.</bpmn:text></bpmn:textAnnotation>
    <bpmn:association id="assoc-credit" sourceRef="check-credit" targetRef="note-credit"/>
    <bpmn:textAnnotation id="note-invoice"><bpmn:text>Sent the day the order is approved.</bpmn:text></bpmn:textAnnotation>
    <bpmn:association id="assoc-invoice" sourceRef="invoice" targetRef="note-invoice"/>
    <bpmn:group id="group-fraud" categoryValueRef="cat-fraud"/>
  </bpmn:process>
</bpmn:definitions>`;

export const SYMBOL_STRUCTURE_LAYOUT = {
  schemaVersion: 1,
  nodes: {
    'receive-order': { x: 60, y: 40, width: 116, height: 80 },
    'check-credit': { x: 260, y: 200, width: 116, height: 80 },
    invoice: { x: 460, y: 360, width: 116, height: 80 },
    paid: { x: 660, y: 382, width: 36, height: 36 },
    'note-credit': { x: 400, y: 196, width: 190, height: 72 },
    'note-invoice': { x: 620, y: 300, width: 150, height: 40 },
    'group-fraud': { x: 230, y: 175, width: 380, height: 290 },
  },
} as const;

export const SYMBOL_STRUCTURE: DemoDefinition = {
  key: 'symbol-structure', name: 'BPMN structure symbols', version: 1, status: 'ACTIVE', effectiveVersion: 1, nodeCount: 4, lastChangedAt: '2026-11-28T09:00:00Z', bpmn: SYMBOL_STRUCTURE_BPMN,
};
